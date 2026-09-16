package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.jvm.BytecodeHelper;
import io.github.potjerodekool.nabu.compiler.backend.native_llvm.config.GraalVMNativeImageConfig.ReflectEntry;
import io.github.potjerodekool.nabu.compiler.backend.native_llvm.config.ReflectionRegistry;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.AnnotationValue;
import io.github.potjerodekool.nabu.lang.model.element.ArrayAttribute;
import io.github.potjerodekool.nabu.lang.model.element.CompoundAttribute;
import io.github.potjerodekool.nabu.lang.model.element.ConstantAttribute;
import io.github.potjerodekool.nabu.lang.model.element.EnumAttribute;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Fase 2: emitteert reflectie-metadata (nabu_reflection_info) voor klassen die
 * via de GraalVM-native-image-config (reflect-config.json) zijn geregistreerd.
 *
 * Per geregistreerde klasse:
 *  - een nabu_field_info-array (naam, JVM-descriptor, byte-offset uit de
 *    object-struct van {@link ClassLayouts}),
 *  - annotatie-entries uit de IR-annotaties (klasse-annotaties op de module,
 *    veld-annotaties met de veldnaam als {@code owner}),
 *  - een nabu_reflection_info-const-global,
 *  - een registratie-functie die {@code nabu_register_reflection} aanroept,
 *    gekoppeld via {@code @llvm.global_ctors}, zodat de runtime de metadata
 *    kent vóór dat de main start.
 *
 * Dit is de ondergrond voor de lite-reflect-subset (Field.get/set,
 * getAnnotation) in de native runtime.
 */
public class ReflectionInfoEmitter {

    private static final int     CTOR_PRIORITY = 65535;

    private final LLVMContextRef          ctx;
    private final LLVMModuleRef           mod;
    private final Map<String, LLVMValueRef> globalValueMap;

    private final Map<String, LLVMValueRef> stringCache = new java.util.LinkedHashMap<>();
    private int                             stringCounter = 0;
    private int                             arrayCounter  = 0;

    private LLVMTypeRef i8PtrParamType;
    private LLVMValueRef registerFn;
    private LLVMTypeRef  registerFnType;

    // LLVM-type handvatten (lazy geïnitialiseerd in emitForClass)
    private LLVMTypeRef fieldInfoType;
    private LLVMTypeRef annotationValueType;
    private LLVMTypeRef annotationEntryType;
    private LLVMTypeRef reflectionInfoType;

    public ReflectionInfoEmitter(final LLVMContextRef ctx,
                                 final LLVMModuleRef mod,
                                 final Map<String, LLVMValueRef> globalValueMap) {
        this.ctx = ctx;
        this.mod = mod;
        this.globalValueMap = globalValueMap;
    }

    /**
     * Emitteert reflectie-metadata voor alle module-klassen met een
     * registry-entry en registreert ze via @llvm.global_ctors.
     */
    public void emit(final LLVMModuleRef mod,
                     final ClassLayouts layouts,
                     final List<IRModule> modules,
                     final ReflectionRegistry registry) {
        if (registry == null || registry.size() == 0) {
            return;
        }
        declareRegisterFunction();

        final List<LLVMValueRef> ctors = new ArrayList<>();
        int index = 0;
        for (final IRModule module : modules) {
            final String internalName = ClassLayouts.normalizeInternalName(module.name);
            if (internalName == null) {
                continue;
            }
            final ReflectEntry entry = registry.entryFor(internalName);
            if (entry == null) {
                continue;
            }
            final ClassLayouts.ClassLayout layout = layouts.get(internalName);
            if (layout == null) {
                continue;
            }
            final LLVMValueRef info = emitForClass(internalName, layout, module, entry, index++);
            if (info != null) {
                ctors.add(emitRegisterFunction(internalName, info));
            }
        }

        if (!ctors.isEmpty()) {
            emitGlobalCtors(ctors);
        }
    }

    // -------------------------------------------------------
    // Per-klasse emissie
    // -------------------------------------------------------

    private LLVMValueRef emitForClass(final String internalName,
                                      final ClassLayouts.ClassLayout layout,
                                      final IRModule module,
                                      final ReflectEntry entry,
                                      final int index) {
        final List<FieldData> fields = collectFields(layout, module, entry);
        final List<AnnotationData> annotations = collectAnnotations(internalName, module);

        final LLVMValueRef fieldsGlobal = fields.isEmpty()
                ? null
                : emitFieldArray(fields);

        // Eén gecombineerde array van nabu_annotation_entry-consts.
        final LLVMValueRef annotationsGlobal = annotations.isEmpty()
                ? null
                : emitAnnotationArray(annotations);

        // struct nabu_reflection_info { i8* type_name, i32 field_count,
        //                               i8* fields, i32 annotation_count, i8* annotations }
        final LLVMTypeRef i8Ptr = LLVMPointerTypeInContext(ctx, 0);
        final LLVMTypeRef i32   = LLVMInt32TypeInContext(ctx);
        reflectionInfoType();

        final LLVMValueRef infoGlobal = LLVMAddGlobal(mod, reflectionInfoType,
                new BytePointer(reflectionInfoGlobalName(internalName)));
        LLVMSetLinkage(infoGlobal, LLVMExternalLinkage);
        LLVMSetGlobalConstant(infoGlobal, 1);

        final PointerPointer<Pointer> init = new PointerPointer<>(5);
        init.put(0, stringLit(internalName));
        init.put(1, LLVMConstInt(i32, fields.size(), 0));
        init.put(2, fieldsGlobal != null ? fieldsGlobal : LLVMConstNull(i8Ptr));
        init.put(3, LLVMConstInt(i32, annotations.size(), 0));
        init.put(4, annotationsGlobal != null ? annotationsGlobal : LLVMConstNull(i8Ptr));
        final LLVMValueRef constInfo = LLVMConstNamedStruct(reflectionInfoType, init, 5);
        LLVMSetInitializer(infoGlobal, constInfo);
        Reference.reachabilityFence(init);
        Reference.reachabilityFence(constInfo);

        globalValueMap.put("@" + reflectionInfoGlobalName(internalName), infoGlobal);
        return infoGlobal;
    }

    private LLVMValueRef emitAnnotationArray(final List<AnnotationData> annotations) {
        annotationEntryType();
        final int n = annotations.size();
        final LLVMTypeRef arrTy = LLVMArrayType(annotationEntryType, n);
        final PointerPointer<Pointer> elems = new PointerPointer<>(n);
        for (int i = 0; i < n; i++) {
            elems.put(i, buildAnnotationEntry(annotations.get(i)));
        }
        final String name = ".refl.annts." + (arrayCounter++);
        final LLVMValueRef arrGlobal = LLVMAddGlobal(mod, arrTy, new BytePointer(name));
        LLVMSetLinkage(arrGlobal, LLVMPrivateLinkage);
        LLVMSetGlobalConstant(arrGlobal, 1);
        LLVMSetInitializer(arrGlobal, LLVMConstArray(annotationEntryType, elems, n));
        globalValueMap.put("@" + name, arrGlobal);
        return arrGlobal;
    }

    // -------------------------------------------------------
    // Veld-metadata
    // -------------------------------------------------------

    private List<FieldData> collectFields(final ClassLayouts.ClassLayout layout,
                                          final IRModule module,
                                          final ReflectEntry entry) {
        final List<String> wanted = entry.fieldNames();
        final List<FieldData> result = new ArrayList<>();
        int counter = 0; // telt ALLE velden (statics mee), net als registerStruct
        for (final IRField field : module.fields()) {
            if (!Flags.hasFlag(field.flags(), Flags.STATIC) && wanted.contains(field.name())) {
                final int structIndex = layout.structIndex(counter);
                if (structIndex > 0) {
                    result.add(new FieldData(
                            field.name(),
                            BytecodeHelper.createDescriptor(field.type()),
                            offsetOfElement(layout, structIndex)));
                }
            }
            counter++;
        }
        return result;
    }

    private long offsetOfElement(final ClassLayouts.ClassLayout layout, final int structIndex) {
        final var dl = LLVMCreateTargetData(new BytePointer(""));
        final long offset = LLVMOffsetOfElement(dl, layout.structType, structIndex);
        LLVMDisposeTargetData(dl);
        return offset;
    }

    private LLVMValueRef emitFieldArray(final List<FieldData> fields) {
        fieldInfoType();
        final int n = fields.size();
        final LLVMTypeRef arrTy = LLVMArrayType(fieldInfoType, n);
        final PointerPointer<Pointer> elems = new PointerPointer<>(n);
        for (int i = 0; i < n; i++) {
            final FieldData f = fields.get(i);
            final PointerPointer<Pointer> fieldInit = new PointerPointer<>(3);
            fieldInit.put(0, stringLit(f.name));
            fieldInit.put(1, stringLit(f.typeDesc));
            fieldInit.put(2, LLVMConstInt(LLVMInt64TypeInContext(ctx), f.offset, 0));
            elems.put(i, LLVMConstNamedStruct(fieldInfoType, fieldInit, 3));
            Reference.reachabilityFence(fieldInit);
        }
        final String name = ".refl.fields." + (arrayCounter++);
        final LLVMValueRef arrGlobal = LLVMAddGlobal(mod, arrTy, new BytePointer(name));
        LLVMSetLinkage(arrGlobal, LLVMPrivateLinkage);
        LLVMSetGlobalConstant(arrGlobal, 1);
        LLVMSetInitializer(arrGlobal, LLVMConstArray(fieldInfoType, elems, n));
        globalValueMap.put("@" + name, arrGlobal);
        return arrGlobal;
    }

    // -------------------------------------------------------
    // Annotaties
    // -------------------------------------------------------

    private List<AnnotationData> collectAnnotations(final String internalName,
                                                    final IRModule module) {
        final List<AnnotationData> result = new ArrayList<>();
        for (final CompoundAttribute ann : module.annotations()) {
            final AnnotationData data = renderAnnotation(ann, null);
            if (data != null) {
                result.add(data);
            }
        }
        for (final IRField field : module.fields()) {
            for (final CompoundAttribute ann : field.annotations()) {
                final AnnotationData data = renderAnnotation(ann, field.name());
                if (data != null) {
                    result.add(data);
                }
            }
        }
        return result;
    }

    private AnnotationData renderAnnotation(final CompoundAttribute ann,
                                            final String owner) {
        final String typeInternal = annotationTypeInternalName(ann);
        if (typeInternal == null) {
            return null;
        }
        final List<ValueData> values = new ArrayList<>();
        for (final Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
                ann.getElementValues().entrySet()) {
            values.add(renderValue(e.getKey().getSimpleName(), e.getValue()));
        }
        return new AnnotationData(typeInternal, owner, values);
    }

    private String annotationTypeInternalName(final CompoundAttribute ann) {
        if (ann.getAnnotationType() == null || ann.getAnnotationType().asElement() == null) {
            return null;
        }
        if (ann.getAnnotationType().asElement() instanceof TypeElement te) {
            return te.getQualifiedName().replace('.', '/');
        }
        return null;
    }

    private ValueData renderValue(final String name, final AnnotationValue value) {
        if (value instanceof ConstantAttribute c) {
            final Object raw = c.getValue();
            if (raw instanceof String s) {
                return new ValueData(name, s, true, 0.0);
            }
            if (raw instanceof Boolean b) {
                return new ValueData(name, null, false, b ? 1.0 : 0.0);
            }
            if (raw instanceof Number n) {
                return new ValueData(name, null, false, n.doubleValue());
            }
            return new ValueData(name, String.valueOf(raw), true, 0.0);
        }
        if (value instanceof ArrayAttribute arr) {
            final StringBuilder sb = new StringBuilder();
            for (final AnnotationValue elem : arr.getValue()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                final Object raw = elem.getValue();
                sb.append(raw != null ? raw : "null");
            }
            return new ValueData(name, sb.toString(), true, 0.0);
        }
        if (value instanceof EnumAttribute en) {
            final var enumValue = en.getValue();
            if (enumValue != null) {
                return new ValueData(name, enumValue.getSimpleName(), true, 0.0);
            }
        }
        final Object raw = value.getValue();
        return new ValueData(name, raw != null ? String.valueOf(raw) : "null", true, 0.0);
    }

    private LLVMValueRef buildAnnotationEntry(final AnnotationData ann) {
        annotationValueType();
        annotationEntryType();

        // waarden-array
        final int n = ann.values.size();
        final LLVMValueRef valuesArray;
        if (n == 0) {
            valuesArray = LLVMConstNull(LLVMPointerTypeInContext(ctx, 0));
        } else {
            final LLVMTypeRef valuesArrTy = LLVMArrayType(annotationValueType, n);
            final PointerPointer<Pointer> valueElems = new PointerPointer<>(n);
            for (int i = 0; i < n; i++) {
                final ValueData v = ann.values.get(i);
                final PointerPointer<Pointer> vInit = new PointerPointer<>(4);
                vInit.put(0, v.name != null ? stringLit(v.name) : LLVMConstNull(LLVMPointerTypeInContext(ctx, 0)));
                vInit.put(1, v.text != null ? stringLit(v.text) : LLVMConstNull(LLVMPointerTypeInContext(ctx, 0)));
                vInit.put(2, LLVMConstInt(LLVMInt32TypeInContext(ctx), v.isString ? 1 : 0, 0));
                vInit.put(3, LLVMConstReal(LLVMDoubleTypeInContext(ctx), v.numValue));
                valueElems.put(i, LLVMConstNamedStruct(annotationValueType, vInit, 4));
                Reference.reachabilityFence(vInit);
            }
            final String vName = ".refl.annval." + (arrayCounter++);
            final LLVMValueRef vGlobal = LLVMAddGlobal(mod, valuesArrTy, new BytePointer(vName));
            LLVMSetLinkage(vGlobal, LLVMPrivateLinkage);
            LLVMSetGlobalConstant(vGlobal, 1);
            LLVMSetInitializer(vGlobal, LLVMConstArray(annotationValueType, valueElems, n));
            globalValueMap.put("@" + vName, vGlobal);
            valuesArray = vGlobal;
        }

        // entry-struct const
        final PointerPointer<Pointer> entryInit = new PointerPointer<>(4);
        entryInit.put(0, stringLit(ann.typeName));
        entryInit.put(1, ann.owner != null ? stringLit(ann.owner) : LLVMConstNull(LLVMPointerTypeInContext(ctx, 0)));
        entryInit.put(2, LLVMConstInt(LLVMInt32TypeInContext(ctx), n, 0));
        entryInit.put(3, valuesArray);
        return LLVMConstNamedStruct(annotationEntryType, entryInit, 4);
    }

    // -------------------------------------------------------
    // Registratie @llvm.global_ctors
    // -------------------------------------------------------

    private void declareRegisterFunction() {
        final LLVMTypeRef i8Ptr = LLVMPointerTypeInContext(ctx, 0);
        final PointerPointer<Pointer> params = new PointerPointer<>(1);
        params.put(0, i8Ptr);
        registerFnType = LLVMFunctionType(LLVMInt32TypeInContext(ctx), params, 1, 0);
        registerFn = LLVMAddFunction(mod,
                new BytePointer("nabu_register_reflection"), registerFnType);
        LLVMSetLinkage(registerFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_register_reflection", registerFn);
        Reference.reachabilityFence(params);
    }

    private LLVMValueRef emitRegisterFunction(final String internalName,
                                              final LLVMValueRef info) {
        final String fnName = "nabu_reflect_init_" + sanitizeName(internalName);
        final LLVMTypeRef voidTy = LLVMVoidTypeInContext(ctx);
        final LLVMTypeRef fnType = LLVMFunctionType(voidTy, new PointerPointer<>(0), 0, 0);

        LLVMValueRef fn = LLVMAddFunction(mod, new BytePointer(fnName), fnType);
        LLVMSetLinkage(fn, LLVMExternalLinkage);

        LLVMBasicBlockRef block = LLVMAppendBasicBlock(fn, "entry");
        LLVMBuilderRef builder = LLVMCreateBuilderInContext(ctx);
        LLVMPositionBuilderAtEnd(builder, block);

        final PointerPointer<Pointer> args = new PointerPointer<>(1);
        args.put(0, info);
        LLVMBuildCall2(builder, registerFnType, registerFn, args, 1, "");
        LLVMBuildRetVoid(builder);
        LLVMDisposeBuilder(builder);

        globalValueMap.put("@" + fnName, fn);
        return fn;
    }

    private void emitGlobalCtors(final List<LLVMValueRef> ctors) {
        final LLVMTypeRef i32  = LLVMInt32TypeInContext(ctx);
        final LLVMTypeRef i8Ptr = LLVMPointerTypeInContext(ctx, 0);

        final PointerPointer<Pointer> entryTypes = new PointerPointer<>(3);
        entryTypes.put(0, i32);
        entryTypes.put(1, i8Ptr);
        entryTypes.put(2, i8Ptr);
        final LLVMTypeRef entryType = LLVMStructTypeInContext(ctx, entryTypes, 3, 0);
        Reference.reachabilityFence(entryTypes);

        final int n = ctors.size();
        final LLVMTypeRef arrType = LLVMArrayType(entryType, n);
        final LLVMValueRef ctorsGlobal = LLVMAddGlobal(mod, arrType,
                new BytePointer("llvm.global_ctors"));
        LLVMSetLinkage(ctorsGlobal, LLVMAppendingLinkage);

        final PointerPointer<Pointer> elems = new PointerPointer<>(n);
        for (int i = 0; i < n; i++) {
            final PointerPointer<Pointer> elemInit = new PointerPointer<>(3);
            elemInit.put(0, LLVMConstInt(i32, CTOR_PRIORITY, 0));
            elemInit.put(1, ctors.get(i));
            elemInit.put(2, LLVMConstNull(i8Ptr));
            elems.put(i, LLVMConstNamedStruct(entryType, elemInit, 3));
            Reference.reachabilityFence(elemInit);
        }
        LLVMSetInitializer(ctorsGlobal, LLVMConstArray(entryType, elems, n));
        globalValueMap.put("@llvm.global_ctors", ctorsGlobal);
        Reference.reachabilityFence(elems);
    }

    // -------------------------------------------------------
    // String- en type-handvatten
    // -------------------------------------------------------

    private LLVMValueRef stringLit(final String text) {
        LLVMValueRef cached = stringCache.get(text);
        if (cached != null) {
            return cached;
        }
        final String name = ".refl.s." + (stringCounter++);
        final LLVMValueRef str = LLVMConstStringInContext(ctx,
                new BytePointer(text), text.length(), 0);
        final LLVMTypeRef strType = LLVMTypeOf(str);
        final LLVMValueRef global = LLVMAddGlobal(mod, strType, new BytePointer(name));
        LLVMSetLinkage(global, LLVMPrivateLinkage);
        LLVMSetGlobalConstant(global, 1);
        LLVMSetInitializer(global, str);
        stringCache.put(text, global);
        return global;
    }

    private void fieldInfoType() {
        if (fieldInfoType == null) {
            final PointerPointer<Pointer> t = new PointerPointer<>(3);
            t.put(0, LLVMPointerTypeInContext(ctx, 0));
            t.put(1, LLVMPointerTypeInContext(ctx, 0));
            t.put(2, LLVMInt64TypeInContext(ctx));
            fieldInfoType = LLVMStructTypeInContext(ctx, t, 3, 0);
            Reference.reachabilityFence(t);
        }
    }

    private void annotationValueType() {
        if (annotationValueType == null) {
            final PointerPointer<Pointer> t = new PointerPointer<>(4);
            t.put(0, LLVMPointerTypeInContext(ctx, 0));
            t.put(1, LLVMPointerTypeInContext(ctx, 0));
            t.put(2, LLVMInt32TypeInContext(ctx));
            t.put(3, LLVMDoubleTypeInContext(ctx));
            annotationValueType = LLVMStructTypeInContext(ctx, t, 4, 0);
            Reference.reachabilityFence(t);
        }
    }

    private void annotationEntryType() {
        if (annotationEntryType == null) {
            final PointerPointer<Pointer> t = new PointerPointer<>(4);
            t.put(0, LLVMPointerTypeInContext(ctx, 0));
            t.put(1, LLVMPointerTypeInContext(ctx, 0));
            t.put(2, LLVMInt32TypeInContext(ctx));
            t.put(3, LLVMPointerTypeInContext(ctx, 0));
            annotationEntryType = LLVMStructTypeInContext(ctx, t, 4, 0);
            Reference.reachabilityFence(t);
        }
    }

    private void reflectionInfoType() {
        if (reflectionInfoType == null) {
            final PointerPointer<Pointer> t = new PointerPointer<>(5);
            t.put(0, LLVMPointerTypeInContext(ctx, 0));
            t.put(1, LLVMInt32TypeInContext(ctx));
            t.put(2, LLVMPointerTypeInContext(ctx, 0));
            t.put(3, LLVMInt32TypeInContext(ctx));
            t.put(4, LLVMPointerTypeInContext(ctx, 0));
            reflectionInfoType = LLVMStructTypeInContext(ctx, t, 5, 0);
            Reference.reachabilityFence(t);
        }
    }

    private static String reflectionInfoGlobalName(final String internalName) {
        return "_ZNabu" + internalName + "Ereflection_info";
    }

    private static String sanitizeName(final String internalName) {
        return internalName.replace('/', '_');
    }

    // -------------------------------------------------------
    // Datamodellen
    // -------------------------------------------------------

    record FieldData(String name, String typeDesc, long offset) {
    }

    record AnnotationData(String typeName, String owner, List<ValueData> values) {
    }

    record ValueData(String name, String text, boolean isString, double numValue) {
    }
}
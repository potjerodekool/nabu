package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRAnnotation;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.jvm.BytecodeHelper;
import io.github.potjerodekool.nabu.backend.nativeimage.GraalVMNativeImageConfig.ReflectEntry;
import io.github.potjerodekool.nabu.backend.nativeimage.ReflectionRegistry;
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

    /** Internal class name → its {@code .clinit.once} function (or null): for static fields. */
    private final java.util.function.Function<String, LLVMValueRef> classInit;

    public ReflectionInfoEmitter(final LLVMContextRef ctx,
                                 final LLVMModuleRef mod,
                                 final Map<String, LLVMValueRef> globalValueMap) {
        this(ctx, mod, globalValueMap, internalName -> null);
    }

    public ReflectionInfoEmitter(final LLVMContextRef ctx,
                                 final LLVMModuleRef mod,
                                 final Map<String, LLVMValueRef> globalValueMap,
                                 final java.util.function.Function<String, LLVMValueRef> classInit) {
        this.ctx = ctx;
        this.mod = mod;
        this.globalValueMap = globalValueMap;
        this.classInit = classInit;
    }

    /**
     * Emitteert reflectie-metadata voor alle module-klassen met een
     * registry-entry en registreert ze via @llvm.global_ctors.
     *
     * @param ctors gedeelde ctor-registratie van de module; de caller emittet
     *              die nadat alle andere constructors zijn toegevoegd
     */
    public void emit(final LLVMModuleRef mod,
                     final ClassLayouts layouts,
                     final List<IRModule> modules,
                     final ReflectionRegistry registry,
                     final ModuleCtors ctors) {
        if (registry == null || registry.size() == 0) {
            return;
        }
        declareRegisterFunction();
        registerSuperclassesForPublicFields(layouts, modules, registry);

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
    }

    /**
     * {@code allPublicFields} also covers inherited public fields
     * ({@code Class.getFields}): the superclasses of such a class get
     * metadata for their public fields too (added to their own entry).
     */
    private static void registerSuperclassesForPublicFields(final ClassLayouts layouts,
                                                            final List<IRModule> modules,
                                                            final ReflectionRegistry registry) {
        for (final IRModule module : modules) {
            final String internalName = ClassLayouts.normalizeInternalName(module.name);
            final ReflectEntry entry = internalName != null ? registry.entryFor(internalName) : null;
            if (entry == null || !entry.allPublicFields()) {
                continue;
            }
            for (String c = layouts.superInternalName(internalName); c != null && !"java/lang/Object".equals(c);
                 c = layouts.superInternalName(c)) {
                final ReflectEntry existing = registry.entryFor(c);
                if (existing == null) {
                    registry.registerIfAbsent(new ReflectEntry(c.replace('/', '.'), false, false, false, false,
                            List.of(), List.of(), false, true));
                } else if (!existing.allPublicFields()) {
                    registry.replace(new ReflectEntry(existing.name(), existing.allDeclaredConstructors(),
                            existing.allPublicConstructors(), existing.allDeclaredMethods(),
                            existing.allPublicMethods(), existing.fields(), existing.methods(),
                            existing.allDeclaredFields(), true));
                }
            }
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
        //                               i8* fields, i32 annotation_count, i8* annotations,
        //                               i8* signature }
        final LLVMTypeRef i8Ptr = LLVMPointerTypeInContext(ctx, 0);
        final LLVMTypeRef i32   = LLVMInt32TypeInContext(ctx);
        reflectionInfoType();

        final LLVMValueRef infoGlobal = LLVMAddGlobal(mod, reflectionInfoType,
                new BytePointer(reflectionInfoGlobalName(internalName)));
        LLVMSetLinkage(infoGlobal, LLVMExternalLinkage);
        LLVMSetGlobalConstant(infoGlobal, 1);

        final PointerPointer<Pointer> init = new PointerPointer<>(6);
        init.put(0, stringLit(internalName));
        init.put(1, LLVMConstInt(i32, fields.size(), 0));
        init.put(2, fieldsGlobal != null ? fieldsGlobal : LLVMConstNull(i8Ptr));
        init.put(3, LLVMConstInt(i32, annotations.size(), 0));
        init.put(4, annotationsGlobal != null ? annotationsGlobal : LLVMConstNull(i8Ptr));
        // The class's generic signature: the bounds of its type variables.
        init.put(5, module.genericSignature() != null ? stringLit(module.genericSignature()) : LLVMConstNull(i8Ptr));
        final LLVMValueRef constInfo = LLVMConstNamedStruct(reflectionInfoType, init, 6);
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
        final List<FieldData> result = new ArrayList<>();
        int counter = 0; // telt ALLE velden (statics mee), net als registerStruct
        for (final IRField field : module.fields()) {
            final boolean isStatic = Flags.hasFlag(field.flags(), Flags.STATIC);
            if (entry.includesField(field.name(), Flags.hasFlag(field.flags(), Flags.PUBLIC))) {
                if (isStatic) {
                    // A static field lives in its global; the runtime
                    // initializes the class before it reads or writes it.
                    final LLVMValueRef global = staticGlobal(layout.internalName, module, field);
                    if (global != null) {
                        result.add(new FieldData(field.name(), BytecodeHelper.createDescriptor(field.type()),
                                0, accessFlags(field.flags()), global, classInit.apply(layout.internalName),
                                field.genericSignature()));
                    }
                } else {
                    final int structIndex = layout.structIndex(counter);
                    if (structIndex > 0) {
                        result.add(new FieldData(
                                field.name(),
                                BytecodeHelper.createDescriptor(field.type()),
                                offsetOfElement(layout, structIndex),
                                accessFlags(field.flags()), null, null, field.genericSignature()));
                    }
                }
            }
            counter++;
        }
        return result;
    }

    /**
     * The global of a static field: the one the code uses is named after
     * the qualified owner ({@code app.Main$Child_counter}); a source module
     * also declares one under the simple owner name, which nothing reads.
     */
    private LLVMValueRef staticGlobal(final String internalName, final IRModule module, final IRField field) {
        final String qualified = internalName.replace('/', '.') + "_" + field.name();
        LLVMValueRef canonical = globalValueMap.get("@" + qualified);
        if (canonical == null || canonical.isNull()) {
            canonical = LLVMGetNamedGlobal(mod, qualified);
        }
        if (canonical != null && !canonical.isNull()) {
            return canonical;
        }
        for (final var global : module.globals().values()) {
            if (global.isStatic() && global.name().endsWith("_" + field.name())) {
                LLVMValueRef value = globalValueMap.get("@" + global.name());
                if (value == null || value.isNull()) {
                    value = LLVMGetNamedGlobal(mod, global.name());
                }
                if (value != null && !value.isNull()) {
                    return value;
                }
            }
        }
        return null;
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
            final LLVMValueRef nullPtr = LLVMConstNull(LLVMPointerTypeInContext(ctx, 0));
            final PointerPointer<Pointer> fieldInit = new PointerPointer<>(7);
            fieldInit.put(0, stringLit(f.name));
            fieldInit.put(1, stringLit(f.typeDesc));
            fieldInit.put(2, LLVMConstInt(LLVMInt64TypeInContext(ctx), f.offset, 0));
            fieldInit.put(3, LLVMConstInt(LLVMInt32TypeInContext(ctx), f.modifiers, 0));
            fieldInit.put(4, f.staticAddress != null ? f.staticAddress : nullPtr);
            fieldInit.put(5, f.classInit != null && !f.classInit.isNull() ? f.classInit : nullPtr);
            fieldInit.put(6, f.signature != null ? stringLit(f.signature) : nullPtr);
            elems.put(i, LLVMConstNamedStruct(fieldInfoType, fieldInit, 7));
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
        for (final IRAnnotation ann : module.bytecodeAnnotations()) {
            result.add(renderBytecodeAnnotation(ann, null));
        }
        for (final IRField field : module.fields()) {
            for (final CompoundAttribute ann : field.annotations()) {
                final AnnotationData data = renderAnnotation(ann, field.name());
                if (data != null) {
                    result.add(data);
                }
            }
            for (final IRAnnotation ann : field.bytecodeAnnotations()) {
                result.add(renderBytecodeAnnotation(ann, field.name()));
            }
        }
        // Methods and constructors: owner "name(descriptor)", a parameter
        // "name(descriptor)#index" (what the runtime's Method looks up).
        for (final IRFunction fn : module.functions()) {
            final boolean hasSource = !fn.annotations().isEmpty()
                    || fn.parameterAnnotations().stream().anyMatch(list -> !list.isEmpty());
            final boolean hasBytecode = !fn.bytecodeAnnotations().isEmpty()
                    || fn.bytecodeParameterAnnotations().stream().anyMatch(list -> !list.isEmpty());
            if (!hasSource && !hasBytecode) {
                continue;
            }
            final String owner = executableKey(module, fn);
            if (owner == null) {
                continue;
            }
            for (final CompoundAttribute ann : fn.annotations()) {
                final AnnotationData data = renderAnnotation(ann, owner);
                if (data != null) {
                    result.add(data);
                }
            }
            for (final IRAnnotation ann : fn.bytecodeAnnotations()) {
                result.add(renderBytecodeAnnotation(ann, owner));
            }
            // Source: the list has a first entry for the receiver of an instance method.
            final List<List<CompoundAttribute>> parameters = fn.parameterAnnotations();
            final int skip = Flags.hasFlag(fn.getFlags(), Flags.STATIC) || parameters.isEmpty() ? 0 : 1;
            for (int i = skip; i < parameters.size(); i++) {
                for (final CompoundAttribute ann : parameters.get(i)) {
                    final AnnotationData data = renderAnnotation(ann, owner + "#" + (i - skip));
                    if (data != null) {
                        result.add(data);
                    }
                }
            }
            final List<List<IRAnnotation>> bytecodeParameters = fn.bytecodeParameterAnnotations();
            for (int i = 0; i < bytecodeParameters.size(); i++) {
                for (final IRAnnotation ann : bytecodeParameters.get(i)) {
                    result.add(renderBytecodeAnnotation(ann, owner + "#" + i));
                }
            }
        }
        return result;
    }

    /**
     * "name(descriptor)" of a method or constructor ({@code <init>}): from
     * the class file for one lowered from bytecode, else rebuilt from the
     * IR (name without its owner prefix and overload suffix, explicit
     * parameter types). Null if the name cannot be told.
     */
    static String executableKey(final IRModule module, final IRFunction fn) {
        if (fn.jvmName() != null && fn.jvmDescriptor() != null) {
            return fn.jvmName() + fn.jvmDescriptor();
        }
        final String name;
        if (fn.isConstructor()) {
            name = "<init>";
        } else {
            final String qualified = module.name + "_";
            String simple = module.name.substring(module.name.lastIndexOf('.') + 1);
            simple = simple.substring(simple.lastIndexOf('$') + 1) + "_";
            String tail;
            if (fn.name.startsWith(qualified)) {
                tail = fn.name.substring(qualified.length());
            } else if (fn.name.startsWith(simple)) {
                tail = fn.name.substring(simple.length());
            } else {
                return null;
            }
            // An overloaded function carries "$P<hash>..." after its name.
            final int overload = tail.indexOf("$P");
            name = overload > 0 ? tail.substring(0, overload) : tail;
        }
        final var descriptor = new StringBuilder("(");
        final boolean hasReceiver = !Flags.hasFlag(fn.getFlags(), Flags.STATIC) && !fn.params.isEmpty();
        for (int i = hasReceiver ? 1 : 0; i < fn.params.size(); i++) {
            descriptor.append(irDescriptor(fn.params.get(i).type()));
        }
        descriptor.append(')').append(fn.isConstructor() ? "V" : irDescriptor(fn.returnType));
        return name + descriptor;
    }

    private static String irDescriptor(final IRType type) {
        if (type instanceof IRType.Int i) {
            return switch (i.bits()) {
                case 8 -> "B";
                case 16 -> i.unsigned() ? "C" : "S";
                case 64 -> "J";
                default -> "I";
            };
        }
        if (type instanceof IRType.Float f) {
            return f.bits() == 64 ? "D" : "F";
        }
        if (type instanceof IRType.Bool) {
            return "Z";
        }
        if (type instanceof IRType.Void || type == null) {
            return "V";
        }
        if (type instanceof IRType.Ptr ptr && ptr.jvmDescriptor() != null) {
            return ptr.jvmDescriptor();
        }
        return "Ljava/lang/Object;";
    }

    /**
     * Een annotatie van een klasse uit bytecode (een library), in hetzelfde
     * formaat als een annotatie uit broncode: tekst voor strings, klassen
     * (interne naam), enum-constanten (naam) en arrays (komma-gescheiden),
     * een getal voor primitieven. Geneste annotaties worden overgeslagen.
     */
    private AnnotationData renderBytecodeAnnotation(final IRAnnotation ann, final String owner) {
        final List<ValueData> values = new ArrayList<>();
        for (final Map.Entry<String, Object> e : ann.values().entrySet()) {
            final Object raw = e.getValue();
            if (raw instanceof Boolean b) {
                values.add(new ValueData(e.getKey(), null, false, b ? 1.0 : 0.0));
            } else if (raw instanceof Number n) {
                values.add(new ValueData(e.getKey(), null, false, n.doubleValue()));
            } else if (raw instanceof Character c) {
                values.add(new ValueData(e.getKey(), null, false, c));
            } else {
                final String text = bytecodeValueText(raw);
                if (text != null) {
                    values.add(new ValueData(e.getKey(), text, true, 0.0));
                }
            }
        }
        return new AnnotationData(ann.typeInternalName(), owner, values);
    }

    private static String bytecodeValueText(final Object raw) {
        if (raw instanceof String s) {
            return s;
        }
        if (raw instanceof org.objectweb.asm.Type type) {
            return type.getSort() == org.objectweb.asm.Type.OBJECT ? type.getInternalName() : type.getDescriptor();
        }
        if (raw instanceof String[] enumConstant && enumConstant.length == 2) {
            return enumConstant[1];
        }
        if (raw instanceof List<?> list) {
            final StringBuilder sb = new StringBuilder();
            for (final Object item : list) {
                final String text = item instanceof Boolean || item instanceof Number || item instanceof Character
                        ? String.valueOf(item)
                        : bytecodeValueText(item);
                if (text == null) {
                    return null;
                }
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(text);
            }
            return sb.toString();
        }
        return null;
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
            // struct nabu_field_info { name, type_desc, offset, modifiers,
            //                          static_address, class_init, signature }
            final PointerPointer<Pointer> t = new PointerPointer<>(7);
            t.put(0, LLVMPointerTypeInContext(ctx, 0));
            t.put(1, LLVMPointerTypeInContext(ctx, 0));
            t.put(2, LLVMInt64TypeInContext(ctx));
            t.put(3, LLVMInt32TypeInContext(ctx));
            t.put(4, LLVMPointerTypeInContext(ctx, 0));
            t.put(5, LLVMPointerTypeInContext(ctx, 0));
            t.put(6, LLVMPointerTypeInContext(ctx, 0));
            fieldInfoType = LLVMStructTypeInContext(ctx, t, 7, 0);
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
            final PointerPointer<Pointer> t = new PointerPointer<>(6);
            t.put(0, LLVMPointerTypeInContext(ctx, 0));
            t.put(1, LLVMInt32TypeInContext(ctx));
            t.put(2, LLVMPointerTypeInContext(ctx, 0));
            t.put(3, LLVMInt32TypeInContext(ctx));
            t.put(4, LLVMPointerTypeInContext(ctx, 0));
            t.put(5, LLVMPointerTypeInContext(ctx, 0));
            reflectionInfoType = LLVMStructTypeInContext(ctx, t, 6, 0);
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

    /** The JVM access flags of a field (Field.getModifiers). */
    private static int accessFlags(final long flags) {
        int access = 0;
        if (Flags.hasFlag(flags, Flags.PUBLIC)) access |= 0x0001;
        if (Flags.hasFlag(flags, Flags.PRIVATE)) access |= 0x0002;
        if (Flags.hasFlag(flags, Flags.PROTECTED)) access |= 0x0004;
        if (Flags.hasFlag(flags, Flags.STATIC)) access |= 0x0008;
        if (Flags.hasFlag(flags, Flags.FINAL)) access |= 0x0010;
        if (Flags.hasFlag(flags, Flags.VOLATILE)) access |= 0x0040;
        if (Flags.hasFlag(flags, Flags.TRANSIENT)) access |= 0x0080;
        if (Flags.hasFlag(flags, Flags.SYNTHETIC)) access |= 0x1000;
        if (Flags.hasFlag(flags, Flags.ENUM)) access |= 0x4000;
        return access;
    }

    record FieldData(String name, String typeDesc, long offset, int modifiers,
                     LLVMValueRef staticAddress, LLVMValueRef classInit, String signature) {
    }

    record AnnotationData(String typeName, String owner, List<ValueData> values) {
    }

    record ValueData(String name, String text, boolean isString, double numValue) {
    }
}
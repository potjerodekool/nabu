package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;

import java.lang.ref.Reference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Orkestreert de volledige vertaling van IRModule naar LLVM IR.
 *
 * Volgorde (verplicht):
 *   1. Globals emitteren (string-literals, variabelen)
 *   2. Functie-signaturen declareren (voor wederzijdse recursie)
 *   3. Functie-bodies emitteren
 */
public class LLVMModuleEmitter {

    private final LLVMContextRef ctx;
    private final LLVMModuleRef  mod;

    // Module-niveau: functies + gedeclareerde globals + string-globals
    private final Map<String, LLVMValueRef>      globalValueMap = new HashMap<>();
    // Per-functie basisblokken (gedeeld met InstructionEmitter)
    private final Map<String, LLVMBasicBlockRef> blockMap       = new HashMap<>();

    private final TypeMapper types;
    private final ConstantResolver   constants;
    private final GlobalEmitter globals;
    private final ClassLayouts classLayouts;
    private final InstructionEmitter instructions;
    private final FunctionEmitter functions;

    private final CompileOptions opts;

    // Fase 2: reflectie-metadata-emissie (kan leeg/null zijn voor single-module tests)
    private io.github.potjerodekool.nabu.backend.nativeimage.ReflectionRegistry reflectionRegistry;

    public LLVMModuleEmitter(LLVMContextRef ctx,
                             LLVMModuleRef mod,
                             LLVMBuilderRef builder,
                             CompileOptions opts) {
        this.ctx   = ctx;
        this.mod   = mod;
        this.opts  = opts;

        this.types       = new TypeMapper(ctx);
        this.constants   = new ConstantResolver(ctx, mod, types, globalValueMap);
        this.classLayouts = new ClassLayouts(ctx, globalValueMap);
        this.globals     = new GlobalEmitter(mod, types, constants, globalValueMap);
        this.instructions = new InstructionEmitter(
                builder, ctx, types, constants, globalValueMap, blockMap, opts, classLayouts);
        this.functions   = new FunctionEmitter(
                ctx, mod, builder, types, constants, instructions, globalValueMap, blockMap, classLayouts);
    }

public void setReflectionRegistry(
            final io.github.potjerodekool.nabu.backend.nativeimage.ReflectionRegistry reflectionRegistry) {
        this.reflectionRegistry = reflectionRegistry;
    }

    /**
     * Vertaalt ǸǸn IRModule naar LLVM IR (single-module compilatie).
     */
    public void emit(IRModule module) {
        emitAll(List.of(module));
    }

    /**
     * Vertaalt meerdere IRModules in één LLVM-module.
     *
     * De passen zijn bewust over álle modules gespreid:
     *   1. globals  + signaturen (wederzijdse recursie én vtable-verwijzingen)
     *   2. objectmodel-registratie (structs, type-info, vtable/itable) —
     *      alle klassen in één keer, zodat super-velden en override-slots
     *      over klassen heen gedeeld kunnen worden
     *   3. bodies
     */
    public void emitAll(List<IRModule> modules) {
        // 0. Runtime helpers declareren
        declareRuntimeHelpers();

        // 0.5 Modules indexeren (naam-normalisatie, functie-resolutie, itable-slot-
        //     volgorde). Moet vóór stap 2 en 2.5: die moeten kunnen zien of een
        //     call-target naar een functie verwijst die hier gedeclareerd is.
        this.classLayouts.indexModules(modules);

        // 1. Globals (string-literals, variabelen) — vóór functies
        for (var module : modules)
            for (var global : module.globals().values())
                globals.emit(global);

        // 2. Alle signaturen — vóór bodies (wederzijdse recursie)
        //    Ook nodig vóór de vtable-opbouw, die functiepointers gebruikt.
        for (var module : modules)
            for (IRFunction fn : module.functions())
                functions.declareSignature(fn);

        // 2.5 Laat-externe callee's: calls naar functies die in geen enkele
        //     te-compileren module als IRFunction gedeclareerd zijn (bv.
        //     interface-calls java.util.Iterator_hasNext). Zonder declaratie
        //     kunnen latere stappen (vtable/itable) geen functiepointer
        //     oplossen.
        for (var module : modules) {
            for (IRFunction fn : module.functions()) {
                if (fn.isExternal()) {
                    continue;
                }
                for (var block : fn.blocks()) {
                    for (IRInstruction instr : block.instructions()) {
                        if (instr instanceof IRInstruction.Call raw) {
                            // Array-clone met eigenaar-owner -> het generieke
                            // Object_clone-symbool (zie CallTargets): dezelfde
                            // normalisatie als de emissie, zodat declaratie en
                            // aanroep hetzelfde symbool delen.
                            final IRInstruction.Call call = this.classLayouts.canonicalize(
                                    CallTargets.normalize(raw));
                            if (call.function() != null
                                && !call.function().endsWith("_super")
                                && !call.function().endsWith("_init")
                                // Per parameterlijst kijken, niet per naam: de
                                // overloads (bv. de zeven println-varianten)
                                // delen de IR-functienaam.
                                && !globalValueMap.containsKey("@" + FnKeys.fnKey(
                                        call.function(), FnKeys.callParams(call)))
                                // Alleen een naam uitbreiden die een eerdere
                                // ad-hoc extern zelf heeft aangemaakt. Is de naam
                                // al vanuit een echte IRFunction gedeclareerd,
                                // dan zou een ad-hoc extern die echte functie
                                // met een verkeerde signatuur overschrijven —
                                // de call sprong dan naar een lege extern stub.
                                && (functions.isAdHocExternal(call.function())
                                    || !globalValueMap.containsKey("@" + call.function()))
                                // Gedeclareerd onder de simple-owner naam: niet
                                // opnieuw als extern verklaren, dat zou de echte
                                // functie overschrijven met een verkeerde signatuur.
                                && this.classLayouts.resolveFunction(call.function()) == null) {
                                functions.declareSignature(call);
                            }
                        }
                    }
                }
            }
        }

        // 3. Objectmodel (struct-layouts + type-info + vtable/itable) — na
        //    signaturen. Ouders vóór kinderen binnen één registerAll.
        this.classLayouts.registerAll(mod, modules);
        emitStringMeta();
        emitPropertiesMeta();
        emitEnumTable(modules);
        emitCtorTable(modules);
        emitExceptionTable(modules);

        // 3.5 Reflectie-metadata (reflect-config-geregistreerde klassen):
        //     nabu_reflection_info-globals + @llvm.global_ctors-registratie.
        //     3.6 GC-init hoort in dezelfde ctor-array: één appending global
        //     per module, dus alles wat vóór main moet draaien staat hier.
        final ModuleCtors ctors = new ModuleCtors(ctx, mod);
        new ReflectionInfoEmitter(ctx, mod, globalValueMap)
                .emit(mod, this.classLayouts, modules, reflectionRegistry, ctors);
        registerBoehmGcInit(ctors);
        registerObjectVtable(ctors);
        registerBootstrapInit(ctors);
        // <clinit>-functies draaien precies één keer (JLS §12.4): lazy via de
        // `.clinit.once`-bewaking bij toegang tot een statisch veld en aan het
        // begin van statische methoden en constructors, en daarnaast eager
        // vóór main voor klassen waarvan niet elke toegang bewaakt wordt
        // (source-klassen met korte eigenaarnamen). Eager wordt ook de
        // once-wrapper aangeroepen, niet de kale initializer: die draaide
        // anders twee keer, en de lazy run vond dan half-geïnitialiseerde of
        // gereset statische velden.
        registerClassInitCtors(ctors, modules);
        ctors.emit();

        // 4. Bodies
        for (var module : modules) {
            for (IRFunction fn : module.functions()) {
                // Dezelfde voorstap als het ASM-backend: phi's zijn SSA-
                // artefacten; vóór de emissie vervangen door per-
                // predecessor Stores (anders verwijst een phi-incoming
                // naar een temp die in een ander blok gedefinieerd is).
                if (!fn.isExternal()) {
                    io.github.potjerodekool.nabu.backend.ir.PhiElimination.run(fn);
                }
                functions.emitBody(fn);
            }
        }

        // 5. Array classes, after the bodies that allocate the arrays.
        emitArrayTypeTable(instructions.arrayTypeNames());
    }

    /**
     * Emits {@code nabu_array_type_names} (index 0 is null, "unknown") and
     * {@code nabu_array_type_count}: the JVM names of the allocated array
     * types, indexed as in the upper bits of each array's element-size word.
     */
    private void emitArrayTypeTable(final List<String> names) {
        final LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        final PointerPointer<Pointer> elems = new PointerPointer<>(names.size() + 1);
        elems.put(0, LLVMConstNull(ptrType));
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            final LLVMValueRef str = LLVMConstStringInContext(ctx, new BytePointer(name), name.length(), 0);
            final LLVMValueRef global = LLVMAddGlobal(mod, LLVMTypeOf(str), new BytePointer(".array.type." + i));
            LLVMSetLinkage(global, LLVMPrivateLinkage);
            LLVMSetGlobalConstant(global, 1);
            LLVMSetInitializer(global, str);
            elems.put(i + 1, global);
        }
        final LLVMTypeRef arrayType = LLVMArrayType(ptrType, names.size() + 1);
        final LLVMValueRef table = LLVMAddGlobal(mod, arrayType, new BytePointer("nabu_array_type_names"));
        LLVMSetInitializer(table, LLVMConstArray(ptrType, elems, names.size() + 1));
        LLVMSetGlobalConstant(table, 1);
        final LLVMValueRef count = LLVMAddGlobal(mod, LLVMInt32TypeInContext(ctx),
                new BytePointer("nabu_array_type_count"));
        LLVMSetInitializer(count, LLVMConstInt(LLVMInt32TypeInContext(ctx), names.size() + 1, 0));
        LLVMSetGlobalConstant(count, 1);
    }

    /**
     * Geeft elke JDK-functie die wél wordt aangeroepen maar nergens een
     * definitie heeft (een native methode, een signature-polymorfe
     * MethodHandle/VarHandle-aanroep, ...) een stub die bij aanroep
     * {@code nabu_unresolved(naam)} aanroept. Zonder definitie faalt de link
     * op een undefined reference, ook als het pad nooit wordt uitgevoerd.
     *
     * <p>Symbolen die de C-runtime levert blijven extern.
     *
     * @return de namen die een stub kregen, gesorteerd.
     */
    public List<String> stubUnresolvedSymbols() {
        final java.util.Set<String> runtime = RuntimeSymbols.load();
        final LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        final PointerPointer<Pointer> trapParams = new PointerPointer<>(1);
        trapParams.put(0, ptrType);
        final LLVMTypeRef trapType = LLVMFunctionType(LLVMVoidTypeInContext(ctx),
                trapParams, 1, 0);
        final LLVMValueRef trap = LLVMAddFunction(mod, "nabu_unresolved", trapType);

        final List<String> stubbed = new java.util.ArrayList<>();
        final LLVMBuilderRef stubBuilder = LLVMCreateBuilderInContext(ctx);
        for (LLVMValueRef fn = LLVMGetFirstFunction(mod); fn != null && !fn.isNull();
             fn = LLVMGetNextFunction(fn)) {
            if (LLVMIsDeclaration(fn) == 0 || LLVMGetIntrinsicID(fn) != 0) continue;
            final var firstUse = LLVMGetFirstUse(fn);
            if (firstUse == null || firstUse.isNull()) continue;
            final String name = LLVMGetValueName(fn).getString();
            // Alleen JDK-leden (`pkg.Owner_member`): C-runtime-, libc- en
            // GC-symbolen hebben geen punt in hun naam.
            if (name.indexOf('.') < 0 || name.startsWith("llvm.") || runtime.contains(name)) {
                continue;
            }
            LLVMPositionBuilderAtEnd(stubBuilder,
                    LLVMAppendBasicBlockInContext(ctx, fn, new BytePointer("entry")));
            final LLVMValueRef symbolName = LLVMBuildGlobalStringPtr(stubBuilder,
                    new BytePointer(name), new BytePointer(".unresolved.name"));
            final PointerPointer<Pointer> args = new PointerPointer<>(1);
            args.put(0, symbolName);
            final boolean nativeSetup = (name.endsWith("_registerNatives")
                    || name.endsWith("_initIDs"))
                    && LLVMGetTypeKind(LLVMGetReturnType(LLVMGlobalGetValueType(fn)))
                            == LLVMVoidTypeKind;
            if (nativeSetup) {
                // Koppelt in een JVM de native methoden van de klasse aan hun
                // implementatie; hier valt er niets te koppelen. Wordt vanuit
                // <clinit> aangeroepen, dus afbreken zou elk programma bij
                // het opstarten stoppen.
                LLVMBuildRetVoid(stubBuilder);
                continue;
            }
            LLVMBuildCall2(stubBuilder, trapType, trap, args, 1, "");
            LLVMBuildUnreachable(stubBuilder);
            java.lang.ref.Reference.reachabilityFence(args);
            stubbed.add(name);
        }
        LLVMDisposeBuilder(stubBuilder);
        java.lang.ref.Reference.reachabilityFence(trapParams);
        java.util.Collections.sort(stubbed);
        return stubbed;
    }

    /**
     * Emitteert {@code nabu_string_meta} (type-info, vtable en itable van
     * java.lang.String) voor de C-runtime en schakelt string-literals over op
     * echte String-objecten. Zonder meegecompileerde String blijft alles
     * nul en blijven strings C-strings.
     */
    private void emitStringMeta() {
        final LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        final ClassLayouts.ClassLayout layout = this.classLayouts.getFor(
                new io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr(
                        io.github.potjerodekool.nabu.backend.ir.types.IRType.I8,
                        "Ljava/lang/String;"));
        LLVMValueRef typeInfo = LLVMGetNamedGlobal(mod,
                new BytePointer("_ZNabujava_lang_StringEtype_info"));
        if (typeInfo != null && typeInfo.isNull()) typeInfo = null;
        final boolean compiled = layout != null && layout.vtableGlobal != null
                && typeInfo != null;

        final LLVMValueRef nullPtr = LLVMConstNull(ptrType);
        final LLVMValueRef[] fields = {
                compiled ? typeInfo : nullPtr,
                compiled ? layout.vtableGlobal : nullPtr,
                compiled && layout.itableGlobal != null ? layout.itableGlobal : nullPtr};
        final LLVMValueRef init = LLVMConstStructInContext(ctx,
                new PointerPointer<>(fields), fields.length, 0);
        final LLVMValueRef meta = LLVMAddGlobal(mod, LLVMTypeOf(init),
                new BytePointer("nabu_string_meta"));
        LLVMSetInitializer(meta, init);
        LLVMSetGlobalConstant(meta, 1);

        if (compiled) {
            constants.setStringLayout(layout.structType, this.classLayouts.objectHeaderType(),
                    typeInfo, layout.vtableGlobal, layout.itableGlobal);
        }
    }

    /**
     * Emitteert {@code nabu_properties_meta}: alles wat de C-runtime nodig
     * heeft om voor {@code System.getProperties()} een échte, uit bytecode
     * gecompileerde {@code java.util.Properties} te bouwen — type-info,
     * vtable, itable, objectgrootte, de no-arg-constructor en
     * {@code setProperty(String, String)}.
     *
     * <p>Een runtime-eigen Properties-object (alleen header plus state-slot)
     * kreeg de vtable van Object; de gecompileerde aanroepers dispatchen via
     * de vtable van Properties en sprongen dan naar slot 28 van een te korte
     * tabel. Ontbreekt iets (Properties niet bereikt), dan blijft alles nul
     * en valt de runtime terug op haar eigen object.
     */
    private void emitPropertiesMeta() {
        final LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        final LLVMTypeRef i64 = LLVMInt64TypeInContext(ctx);
        final ClassLayouts.ClassLayout layout = this.classLayouts.getFor(
                new io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr(
                        io.github.potjerodekool.nabu.backend.ir.types.IRType.I8,
                        "Ljava/util/Properties;"));
        LLVMValueRef typeInfo = LLVMGetNamedGlobal(mod,
                new BytePointer("_ZNabujava_util_PropertiesEtype_info"));
        if (typeInfo != null && typeInfo.isNull()) typeInfo = null;
        final LLVMValueRef ctor = definedFunction("java/util/Properties",
                "java.util.Properties_init", List.of());
        final LLVMValueRef setProperty = definedFunction("java/util/Properties",
                "java.util.Properties_setProperty", List.of("Ljava/lang/String;", "Ljava/lang/String;"));
        final boolean compiled = layout != null && layout.vtableGlobal != null
                && typeInfo != null && ctor != null && setProperty != null;

        final LLVMValueRef nullPtr = LLVMConstNull(ptrType);
        long size = 0;
        if (compiled) {
            final LLVMTargetDataRef dl = LLVMCreateTargetData(new BytePointer(""));
            size = LLVMABISizeOfType(dl, layout.structType);
            LLVMDisposeTargetData(dl);
        }
        final LLVMValueRef[] fields = {
                compiled ? typeInfo : nullPtr,
                compiled ? layout.vtableGlobal : nullPtr,
                compiled && layout.itableGlobal != null ? layout.itableGlobal : nullPtr,
                LLVMConstInt(i64, size, 0),
                compiled ? ctor : nullPtr,
                compiled ? setProperty : nullPtr};
        final LLVMValueRef init = LLVMConstStructInContext(ctx,
                new PointerPointer<>(fields), fields.length, 0);
        final LLVMValueRef meta = LLVMAddGlobal(mod, LLVMTypeOf(init),
                new BytePointer("nabu_properties_meta"));
        LLVMSetInitializer(meta, init);
        LLVMSetGlobalConstant(meta, 1);
    }

    /**
     * Emitteert {@code nabu_ctor_table} ({@code { i8* internalName, ptr type,
     * ptr vtable, ptr itable, i64 size, ptr init }} per instantieerbare
     * klasse met een no-arg-constructor) en {@code nabu_ctor_count}. Daarmee
     * implementeert de runtime {@code Constructor.newInstance()} en
     * {@code Class.newInstance()} (Class en Constructor zijn VM-gebonden) door
     * een echt, gecompileerd object te bouwen. De JDK instantieert zo o.a. zijn
     * locale-provider-adapters ({@code LocaleProviderAdapter.forType}), wat
     * String.format met {@code %d} nodig heeft.
     */
    private void emitCtorTable(final List<IRModule> modules) {
        final LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        final LLVMTypeRef i64 = LLVMInt64TypeInContext(ctx);
        final PointerPointer<Pointer> memberTypes = new PointerPointer<>(6);
        for (int i = 0; i < 6; i++) {
            memberTypes.put(i, i == 4 ? i64 : ptrType);
        }
        final LLVMTypeRef entryType = LLVMStructTypeInContext(ctx, memberTypes, 6, 0);
        final List<LLVMValueRef> entries = new java.util.ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        final LLVMTargetDataRef dl = LLVMCreateTargetData(new BytePointer(""));
        int index = 0;
        for (final IRModule module : modules) {
            if (io.github.potjerodekool.nabu.lang.Flags.hasFlag(module.flags, io.github.potjerodekool.nabu.lang.Flags.ABSTRACT)
                    || io.github.potjerodekool.nabu.lang.Flags.hasFlag(module.flags, io.github.potjerodekool.nabu.lang.Flags.INTERFACE)) {
                continue;
            }
            final String internalName = ClassLayouts.normalizeInternalName(module.name);
            if (internalName == null || !seen.add(internalName)) {
                continue;
            }
            final IRFunction init = module.functions().stream()
                    .filter(fn -> fn.name != null && fn.name.endsWith("_init") && fn.isConstructor()
                            && fn.params.size() == 1 && !fn.isExternal() && !fn.blocks().isEmpty())
                    .findFirst().orElse(null);
            if (init == null) {
                continue;
            }
            final LLVMValueRef initValue = this.classLayouts.functionValue(init);
            final ClassLayouts.ClassLayout layout = this.classLayouts.getFor(
                    new io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr(
                            io.github.potjerodekool.nabu.backend.ir.types.IRType.I8,
                            "L" + internalName + ";"));
            LLVMValueRef typeInfo = LLVMGetNamedGlobal(mod,
                    new BytePointer("_ZNabu" + this.classLayouts.typeSymbol(internalName) + "Etype_info"));
            if (initValue == null || initValue.isNull() || layout == null || layout.vtableGlobal == null
                    || typeInfo == null || typeInfo.isNull()) {
                continue;
            }
            final LLVMValueRef nameStr = LLVMConstStringInContext(ctx, new BytePointer(internalName),
                    internalName.length(), 0);
            final LLVMValueRef nameGlobal = LLVMAddGlobal(mod, LLVMTypeOf(nameStr),
                    new BytePointer(".ctor.name." + (index++)));
            LLVMSetLinkage(nameGlobal, LLVMPrivateLinkage);
            LLVMSetGlobalConstant(nameGlobal, 1);
            LLVMSetInitializer(nameGlobal, nameStr);
            final LLVMValueRef[] fields = {
                    nameGlobal,
                    typeInfo,
                    layout.vtableGlobal,
                    layout.itableGlobal != null ? layout.itableGlobal : LLVMConstNull(ptrType),
                    LLVMConstInt(i64, LLVMABISizeOfType(dl, layout.structType), 0),
                    initValue};
            entries.add(LLVMConstNamedStruct(entryType, new PointerPointer<>(fields), fields.length));
        }
        LLVMDisposeTargetData(dl);
        final PointerPointer<Pointer> elems = new PointerPointer<>(Math.max(1, entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            elems.put(i, entries.get(i));
        }
        final LLVMTypeRef arrayType = LLVMArrayType(entryType, entries.size());
        final LLVMValueRef table = LLVMAddGlobal(mod, arrayType, new BytePointer("nabu_ctor_table"));
        LLVMSetInitializer(table, LLVMConstArray(entryType, elems, entries.size()));
        LLVMSetGlobalConstant(table, 1);
        final LLVMValueRef count = LLVMAddGlobal(mod, LLVMInt32TypeInContext(ctx), new BytePointer("nabu_ctor_count"));
        LLVMSetInitializer(count, LLVMConstInt(LLVMInt32TypeInContext(ctx), entries.size(), 0));
        LLVMSetGlobalConstant(count, 1);
    }

    /**
     * Emits {@code nabu_exception_table} ({@code { i8* internalName, ptr type,
     * ptr vtable, ptr itable, i64 size, ptr init(self, String) }}) and
     * {@code nabu_exception_count} for the exceptions the C runtime throws
     * itself ({@link io.github.potjerodekool.nabu.backend.RuntimeExceptions}),
     * so that {@code nabu_new_exception} creates real objects. Before, the
     * runtime allocated a header-only shell, and {@code getMessage()} on a
     * caught exception read past its end.
     */
    private void emitExceptionTable(final List<IRModule> modules) {
        final LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        final LLVMTypeRef i64 = LLVMInt64TypeInContext(ctx);
        final PointerPointer<Pointer> memberTypes = new PointerPointer<>(6);
        for (int i = 0; i < 6; i++) {
            memberTypes.put(i, i == 4 ? i64 : ptrType);
        }
        final LLVMTypeRef entryType = LLVMStructTypeInContext(ctx, memberTypes, 6, 0);
        final List<LLVMValueRef> entries = new java.util.ArrayList<>();
        final java.util.Set<String> wanted = new java.util.HashSet<>(
                io.github.potjerodekool.nabu.backend.RuntimeExceptions.CLASSES);
        final LLVMTargetDataRef dl = LLVMCreateTargetData(new BytePointer(""));
        int index = 0;
        for (final IRModule module : modules) {
            final String internalName = ClassLayouts.normalizeInternalName(module.name);
            if (internalName == null || !wanted.remove(internalName)) {
                continue;
            }
            final IRFunction init = module.functions().stream()
                    .filter(fn -> fn.name != null && fn.name.endsWith("_init") && fn.isConstructor()
                            && fn.params.size() == 2 && !fn.isExternal() && !fn.blocks().isEmpty()
                            && fn.params.get(1).type() instanceof io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr p
                            && "Ljava/lang/String;".equals(p.jvmDescriptor()))
                    .findFirst().orElse(null);
            if (init == null) {
                continue;
            }
            final LLVMValueRef initValue = this.classLayouts.functionValue(init);
            final ClassLayouts.ClassLayout layout = this.classLayouts.getFor(
                    new io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr(
                            io.github.potjerodekool.nabu.backend.ir.types.IRType.I8,
                            "L" + internalName + ";"));
            final LLVMValueRef typeInfo = LLVMGetNamedGlobal(mod,
                    new BytePointer("_ZNabu" + this.classLayouts.typeSymbol(internalName) + "Etype_info"));
            if (initValue == null || initValue.isNull() || layout == null || layout.vtableGlobal == null
                    || typeInfo == null || typeInfo.isNull()) {
                continue;
            }
            final LLVMValueRef nameStr = LLVMConstStringInContext(ctx, new BytePointer(internalName),
                    internalName.length(), 0);
            final LLVMValueRef nameGlobal = LLVMAddGlobal(mod, LLVMTypeOf(nameStr),
                    new BytePointer(".exception.name." + (index++)));
            LLVMSetLinkage(nameGlobal, LLVMPrivateLinkage);
            LLVMSetGlobalConstant(nameGlobal, 1);
            LLVMSetInitializer(nameGlobal, nameStr);
            final LLVMValueRef[] fields = {
                    nameGlobal,
                    typeInfo,
                    layout.vtableGlobal,
                    layout.itableGlobal != null ? layout.itableGlobal : LLVMConstNull(ptrType),
                    LLVMConstInt(i64, LLVMABISizeOfType(dl, layout.structType), 0),
                    initValue};
            entries.add(LLVMConstNamedStruct(entryType, new PointerPointer<>(fields), fields.length));
        }
        LLVMDisposeTargetData(dl);
        final PointerPointer<Pointer> elems = new PointerPointer<>(Math.max(1, entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            elems.put(i, entries.get(i));
        }
        final LLVMTypeRef arrayType = LLVMArrayType(entryType, entries.size());
        final LLVMValueRef table = LLVMAddGlobal(mod, arrayType, new BytePointer("nabu_exception_table"));
        LLVMSetInitializer(table, LLVMConstArray(entryType, elems, entries.size()));
        LLVMSetGlobalConstant(table, 1);
        final LLVMValueRef count = LLVMAddGlobal(mod, LLVMInt32TypeInContext(ctx),
                new BytePointer("nabu_exception_count"));
        LLVMSetInitializer(count, LLVMConstInt(LLVMInt32TypeInContext(ctx), entries.size(), 0));
        LLVMSetGlobalConstant(count, 1);
    }

    /**
     * Emitteert {@code nabu_enum_table} ({@code { i8* internalName, ptr values }}
     * per enum-klasse) en {@code nabu_enum_count}: daarmee implementeert de
     * runtime Class.isEnum/getEnumConstants (Class is VM-gebonden) door de
     * echte, gecompileerde {@code values()} aan te roepen. Enum.valueOf — o.a.
     * picocli's TraceLevel en enum-opties — loopt daarlangs (zie
     * EnumSubstitution).
     */
    private void emitEnumTable(final List<IRModule> modules) {
        final LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        final PointerPointer<Pointer> memberTypes = new PointerPointer<>(2);
        memberTypes.put(0, ptrType);
        memberTypes.put(1, ptrType);
        final LLVMTypeRef entryType = LLVMStructTypeInContext(ctx, memberTypes, 2, 0);
        final List<LLVMValueRef> entries = new java.util.ArrayList<>();
        int index = 0;
        for (final IRModule module : modules) {
            if (!io.github.potjerodekool.nabu.lang.Flags.hasFlag(module.flags, io.github.potjerodekool.nabu.lang.Flags.ENUM)) {
                continue;
            }
            final IRFunction values = module.functions().stream()
                    .filter(fn -> fn.name != null && fn.name.endsWith("_values") && fn.params.isEmpty()
                            && !fn.blocks().isEmpty()
                            && io.github.potjerodekool.nabu.lang.Flags.hasFlag(fn.getFlags(),
                                    io.github.potjerodekool.nabu.lang.Flags.STATIC))
                    .findFirst().orElse(null);
            final LLVMValueRef fnValue = values != null ? this.classLayouts.functionValue(values) : null;
            if (fnValue == null || fnValue.isNull()) {
                continue;
            }
            final String internalName = ClassLayouts.normalizeInternalName(module.name);
            final LLVMValueRef nameStr = LLVMConstStringInContext(ctx, new BytePointer(internalName),
                    internalName.length(), 0);
            final LLVMValueRef nameGlobal = LLVMAddGlobal(mod, LLVMTypeOf(nameStr),
                    new BytePointer(".enum.name." + (index++)));
            LLVMSetLinkage(nameGlobal, LLVMPrivateLinkage);
            LLVMSetGlobalConstant(nameGlobal, 1);
            LLVMSetInitializer(nameGlobal, nameStr);
            final PointerPointer<Pointer> members = new PointerPointer<>(2);
            members.put(0, nameGlobal);
            members.put(1, fnValue);
            entries.add(LLVMConstNamedStruct(entryType, members, 2));
        }
        final PointerPointer<Pointer> elems = new PointerPointer<>(Math.max(1, entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            elems.put(i, entries.get(i));
        }
        final LLVMTypeRef arrayType = LLVMArrayType(entryType, entries.size());
        final LLVMValueRef table = LLVMAddGlobal(mod, arrayType, new BytePointer("nabu_enum_table"));
        LLVMSetInitializer(table, LLVMConstArray(entryType, elems, entries.size()));
        LLVMSetGlobalConstant(table, 1);
        final LLVMValueRef count = LLVMAddGlobal(mod, LLVMInt32TypeInContext(ctx), new BytePointer("nabu_enum_count"));
        LLVMSetInitializer(count, LLVMConstInt(LLVMInt32TypeInContext(ctx), entries.size(), 0));
        LLVMSetGlobalConstant(count, 1);
    }

    /**
     * De LLVM-functie van de instantiemethode {@code name} van
     * {@code ownerInternalName} met precies deze expliciete parametertypes
     * (JVM-descriptors; een primitief als {@code "Z"} e.d.), mits het module
     * haar met een body definieert; anders null (een declaratie zonder body
     * wordt later een abort-stub). Overloads delen de IR-naam, dus de keuze
     * gaat op de parameters.
     */
    private LLVMValueRef definedFunction(final String ownerInternalName,
                                         final String name,
                                         final List<String> paramDescriptors) {
        final IRModule owner = this.classLayouts.moduleOf(ownerInternalName);
        if (owner == null) {
            return null;
        }
        for (final IRFunction fn : owner.functions()) {
            if (!name.equals(fn.name) || fn.isExternal() || fn.blocks().isEmpty()
                    || fn.params.size() != paramDescriptors.size() + 1) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < paramDescriptors.size() && matches; i++) {
                matches = paramDescriptors.get(i).equals(descriptorOf(fn.params.get(i + 1).type()));
            }
            if (matches) {
                final LLVMValueRef value = this.classLayouts.functionValue(fn);
                return value != null && !value.isNull() ? value : null;
            }
        }
        return null;
    }

    private static String descriptorOf(final io.github.potjerodekool.nabu.backend.ir.types.IRType type) {
        if (type instanceof io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr p) {
            return p.jvmDescriptor();
        }
        if (type instanceof io.github.potjerodekool.nabu.backend.ir.types.IRType.Bool) {
            return "Z";
        }
        if (type instanceof io.github.potjerodekool.nabu.backend.ir.types.IRType.Int i) {
            return switch (i.bits()) {
                case 8 -> "B";
                case 16 -> "S";
                case 64 -> "J";
                default -> "I";
            };
        }
        return null;
    }

    /**
     * Roept de gesynthetiseerde {@code nabu.runtime.Bootstrap.init()} aan
     * (zie BytecodeReachabilityCompiler.BOOTSTRAP_CLASS): het deel van
     * {@code System.initPhase1} dat de JDK-bytecode nodig heeft — de
     * JavaLangAccess-implementatie en System.out/err — als bytecode. Draait
     * vóór de {@code <clinit>}s; zonder bytecode-sluiting bestaat de functie
     * niet en gebeurt er niets.
     */
    /**
     * Eén module-constructor per source-klasse met een {@code <clinit>}: haar
     * once-wrapper. Uit bytecode gecompileerde klassen ({@code pkg.Owner_clinit})
     * zijn volledig bewaakt en initialiseren puur lazy, zoals op de JVM: eager
     * draaiden anders ook initializers van JDK-klassen die het programma nooit
     * gebruikt (DateTimeFormatterBuilder.<clinit> gooide InternalError).
     */
    private void registerClassInitCtors(final ModuleCtors ctors, final List<IRModule> modules) {
        for (final IRModule module : modules) {
            final boolean hasClinit = module.functions().stream()
                    .anyMatch(fn -> fn.name != null && !fn.blocks().isEmpty() && fn.params.isEmpty()
                            && fn.name.contains("<clinit>"));
            if (!hasClinit) {
                continue;
            }
            final LLVMValueRef once = instructions.classInitFunction(
                    ClassLayouts.normalizeInternalName(module.name), mod);
            if (once != null && !once.isNull()) {
                ctors.add(once);
            }
        }
    }

    private void registerBootstrapInit(final ModuleCtors ctors) {
        final IRModule bootstrap = this.classLayouts.moduleOf("nabu/runtime/Bootstrap");
        if (bootstrap == null) {
            return;
        }
        final IRFunction init = bootstrap.functions().stream()
                .filter(fn -> fn.name.equals("nabu.runtime.Bootstrap_init") && fn.params.isEmpty()
                        && !fn.blocks().isEmpty())
                .findFirst().orElse(null);
        final LLVMValueRef initFn = init != null ? this.classLayouts.functionValue(init) : null;
        if (initFn == null || initFn.isNull()) {
            return;
        }
        ctors.add(initFn);
    }

    private void declareRuntimeHelpers() {
        LLVMTypeRef i8ptr = LLVMPointerTypeInContext(ctx, 0);
        LLVMTypeRef i64   = LLVMInt64TypeInContext(ctx);

        // --- InstanceOf (altijd nodig) ---
        // int nabu_instanceof(void* obj, i8* type_name). Let op: de retourtype
        // is i32 — de C-functie retourneert een int (0/1), en de emitter
        // trunct die naar i1.
        PointerPointer<Pointer> instanceofParams = new PointerPointer<>(2);
        instanceofParams.put(0, i8ptr);
        instanceofParams.put(1, i8ptr);
        LLVMTypeRef instanceofFnType = LLVMFunctionType(LLVMInt32TypeInContext(ctx),
                instanceofParams, 2, 0);
        LLVMValueRef instanceofFn = LLVMAddFunction(mod,
                new BytePointer("nabu_instanceof"), instanceofFnType);
        LLVMSetLinkage(instanceofFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_instanceof", instanceofFn);

        // --- Throw (altijd nodig) ---
        PointerPointer<Pointer> throwParams = new PointerPointer<>(1);
        throwParams.put(0, i8ptr);
        LLVMTypeRef throwFnType = LLVMFunctionType(LLVMVoidTypeInContext(ctx), throwParams, 1, 0);
        LLVMValueRef throwFn = LLVMAddFunction(mod,
                new BytePointer("nabu_throw"), throwFnType);
        LLVMSetLinkage(throwFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_throw", throwFn);

        // --- Catch (altijd nodig voor exception handling) ---
        // void* nabu_catch(void* unwind_exc)
        PointerPointer<Pointer> catchParams = new PointerPointer<>(1);
        catchParams.put(0, i8ptr);
        LLVMTypeRef catchFnType = LLVMFunctionType(i8ptr, catchParams, 1, 0);
        LLVMValueRef catchFn = LLVMAddFunction(mod,
                new BytePointer("nabu_catch"), catchFnType);
        LLVMSetLinkage(catchFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_catch", catchFn);

        // --- CanCatch (catch type-filtering; altijd nodig) ---
        // int nabu_can_catch(void* obj, i8* type_name)
        PointerPointer<Pointer> canCatchParams = new PointerPointer<>(2);
        canCatchParams.put(0, i8ptr);
        canCatchParams.put(1, i8ptr);
        LLVMTypeRef canCatchFnType = LLVMFunctionType(LLVMInt32TypeInContext(ctx),
                canCatchParams, 2, 0);
        LLVMValueRef canCatchFn = LLVMAddFunction(mod,
                new BytePointer("nabu_can_catch"), canCatchFnType);
        LLVMSetLinkage(canCatchFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_can_catch", canCatchFn);

        // --- Resume (rethrow van een niet-gewipte exception) ---
        // void nabu_resume(void* unwind_exc): vervolgt de unwind van een
        // exception die dit landingspad bereikte, richting de omringende
        // handlers. Niet een nieuwe throw: die zou de same-frame handlers
        // overslaan. Zie runtime/nabu_runtime.h.
        PointerPointer<Pointer> resumeParams = new PointerPointer<>(1);
        resumeParams.put(0, i8ptr);
        LLVMTypeRef resumeFnType = LLVMFunctionType(LLVMVoidTypeInContext(ctx),
                resumeParams, 1, 0);
        LLVMValueRef resumeFn = LLVMAddFunction(mod,
                new BytePointer("nabu_resume"), resumeFnType);
        LLVMSetLinkage(resumeFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_resume", resumeFn);

        // --- MonitorEnter / MonitorExit (altijd nodig voor synchronized) ---
        PointerPointer<Pointer> monitorParams = new PointerPointer<>(1);
        monitorParams.put(0, i8ptr);
        LLVMTypeRef monitorFnType = LLVMFunctionType(LLVMVoidTypeInContext(ctx), monitorParams, 1, 0);
        LLVMValueRef monitorEnterFn = LLVMAddFunction(mod,
                new BytePointer("nabu_monitorenter"), monitorFnType);
        LLVMSetLinkage(monitorEnterFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_monitorenter", monitorEnterFn);

        LLVMValueRef monitorExitFn = LLVMAddFunction(mod,
                new BytePointer("nabu_monitorexit"), monitorFnType);
        LLVMSetLinkage(monitorExitFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_monitorexit", monitorExitFn);

        // --- String-concat (altijd nodig) ---
        // i8* nabu_concat(i8* a, i8* b)
        PointerPointer<Pointer> concatParams = new PointerPointer<>(2);
        concatParams.put(0, i8ptr);
        concatParams.put(1, i8ptr);
        LLVMTypeRef concatFnType = LLVMFunctionType(i8ptr, concatParams, 2, 0);
        LLVMValueRef concatFn = LLVMAddFunction(mod,
                new BytePointer("nabu_concat"), concatFnType);
        LLVMSetLinkage(concatFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_concat", concatFn);

        // --- argv -> String[] (altijd nodig voor de main()-bridge) ---
        // i8* nabu_args_from_argv(i32 argc, i8* argv). 'argv' is in de
        // LLVM-IR van deze backend een opaque i8*, net als elke andere
        // pointer (zie i8ptr hierboven) -- de C-kant (jcl_lite.c) kent het
        // echte char** type.
        PointerPointer<Pointer> argsFromArgvParams = new PointerPointer<>(2);
        argsFromArgvParams.put(0, LLVMInt32TypeInContext(ctx));
        argsFromArgvParams.put(1, i8ptr);
        LLVMTypeRef argsFromArgvFnType = LLVMFunctionType(i8ptr, argsFromArgvParams, 2, 0);
        LLVMValueRef argsFromArgvFn = LLVMAddFunction(mod,
                new BytePointer("nabu_args_from_argv"), argsFromArgvFnType);
        LLVMSetLinkage(argsFromArgvFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_args_from_argv", argsFromArgvFn);

        // --- Primitief -> string (voor concat met een numerieke operand) ---
        // i8* nabu_int_to_string(i32), i8* nabu_long_to_string(i64)
        LLVMValueRef intToString = LLVMAddFunction(mod,
                new BytePointer("nabu_int_to_string"),
                LLVMFunctionType(i8ptr,
                        new PointerPointer<>(new Pointer[] { LLVMInt32TypeInContext(ctx) }), 1, 0));
        LLVMSetLinkage(intToString, LLVMExternalLinkage);
        globalValueMap.put("@nabu_int_to_string", intToString);
        LLVMValueRef longToString = LLVMAddFunction(mod,
                new BytePointer("nabu_long_to_string"),
                LLVMFunctionType(i8ptr,
                        new PointerPointer<>(new Pointer[] { LLVMInt64TypeInContext(ctx) }), 1, 0));
        LLVMSetLinkage(longToString, LLVMExternalLinkage);
        globalValueMap.put("@nabu_long_to_string", longToString);
        LLVMValueRef doubleToString = LLVMAddFunction(mod,
                new BytePointer("nabu_double_to_string"),
                LLVMFunctionType(i8ptr,
                        new PointerPointer<>(new Pointer[] { LLVMDoubleTypeInContext(ctx) }), 1, 0));
        LLVMSetLinkage(doubleToString, LLVMExternalLinkage);
        globalValueMap.put("@nabu_double_to_string", doubleToString);
        LLVMValueRef floatToString = LLVMAddFunction(mod,
                new BytePointer("nabu_float_to_string"),
                LLVMFunctionType(i8ptr,
                        new PointerPointer<>(new Pointer[] { LLVMFloatTypeInContext(ctx) }), 1, 0));
        LLVMSetLinkage(floatToString, LLVMExternalLinkage);
        globalValueMap.put("@nabu_float_to_string", floatToString);
        // i8* nabu_bool_to_string(i32) — de i1 operand wordt bij de call
        // uitgebreid naar i32, zodat de C-signatuur integer blijft.
        LLVMValueRef boolToString = LLVMAddFunction(mod,
                new BytePointer("nabu_bool_to_string"),
                LLVMFunctionType(i8ptr,
                        new PointerPointer<>(new Pointer[] { LLVMInt32TypeInContext(ctx) }), 1, 0));
        LLVMSetLinkage(boolToString, LLVMExternalLinkage);
        globalValueMap.put("@nabu_bool_to_string", boolToString);
        // i8* nabu_object_to_string(i8*) — String.valueOf(Object) via het
        // vtable-slot van toString (zie nabu_set_object_tostring_slot).
        LLVMValueRef objectToString = LLVMAddFunction(mod,
                new BytePointer("nabu_object_to_string"),
                LLVMFunctionType(i8ptr, new PointerPointer<>(new Pointer[] { i8ptr }), 1, 0));
        LLVMSetLinkage(objectToString, LLVMExternalLinkage);
        globalValueMap.put("@nabu_object_to_string", objectToString);
        LLVMValueRef setToStringSlot = LLVMAddFunction(mod,
                new BytePointer("nabu_set_object_tostring_slot"),
                LLVMFunctionType(LLVMVoidTypeInContext(ctx),
                        new PointerPointer<>(new Pointer[] { LLVMInt32TypeInContext(ctx) }), 1, 0));
        LLVMSetLinkage(setToStringSlot, LLVMExternalLinkage);
        globalValueMap.put("@nabu_set_object_tostring_slot", setToStringSlot);
        LLVMValueRef setBoxFn = LLVMAddFunction(mod,
                new BytePointer("nabu_set_box_fn"),
                LLVMFunctionType(LLVMVoidTypeInContext(ctx),
                        new PointerPointer<>(new Pointer[] { LLVMInt32TypeInContext(ctx), i8ptr }), 2, 0));
        LLVMSetLinkage(setBoxFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_set_box_fn", setBoxFn);

        // --- Personality functie voor exception handling ---
        // Nabu-eigen SEH-persoonlijkheid (nabu_seh_personality): een 4-arg
        // SEH-wrapper die in .xdata wordt geregistreerd en via
        // _GCC_specific_handler naar onze Itanium-persoonlijkheid
        // (nabu_itanium_personality) doorstuurt. Deze leest vervolgens de LSDA
        // (.gcc_except_table) die LLVM emitteert voor landingpads met catch-clauses.
        // NB: Key blijft "@__gcc_personality_v0" (FunctionEmitter gebruikt die),
        //      maar de *geëmitteerde symboolnaam* is nu nabu_seh_personality.
        LLVMTypeRef personalityFnType = LLVMFunctionType(LLVMInt32TypeInContext(ctx),
                new PointerPointer<>(0), 0, 1);
        LLVMValueRef personalityFn = LLVMAddFunction(mod,
                new BytePointer("nabu_seh_personality"), personalityFnType);
        LLVMSetLinkage(personalityFn, LLVMExternalLinkage);
        globalValueMap.put("@__gcc_personality_v0", personalityFn);

        // --- Exception typeinfo-marker voor LSDA-emissie ---
        // Landingpads moeten een echte catch-clause hebben voordat LLVM een
        // LSDA (.gcc_except_table) emitteert. De nabu-type-filtering gebeurt in
        // code via nabu_can_catch, dus de clause hoeft geen specifiek type te
        // zijn. Deze gegarandeerd aanwezige typeinfo-global dient als
        // LSDA-trigger-clause; FunctionEmitter refereert hem via
        // "@nabu_exception_typeinfo".
        LLVMValueRef excNameStr = LLVMConstStringInContext(ctx,
                new BytePointer("nabu/exceptions"), "nabu/exceptions".length(), 0);
        LLVMValueRef excNameGlobal = LLVMAddGlobal(mod, LLVMTypeOf(excNameStr),
                new BytePointer(".exc.marker"));
        LLVMSetLinkage(excNameGlobal, LLVMPrivateLinkage);
        LLVMSetGlobalConstant(excNameGlobal, 1);
        LLVMSetInitializer(excNameGlobal, excNameStr);

        // struct nabu_type_info { i8* name, i64 size, i8* super_type, i32 field_count,
        //                         i8** interfaces, i32 interface_count,
        //                         nabu_type_field* instance_fields, i32 instance_field_count }
        LLVMTypeRef i32 = LLVMInt32TypeInContext(ctx);
        PointerPointer<Pointer> excInfoTypes = new PointerPointer<>(8);
        excInfoTypes.put(0, i8ptr);
        excInfoTypes.put(1, i64);
        excInfoTypes.put(2, i8ptr);
        excInfoTypes.put(3, i32);
        excInfoTypes.put(4, i8ptr);
        excInfoTypes.put(5, i32);
        excInfoTypes.put(6, i8ptr);
        excInfoTypes.put(7, i32);
        LLVMTypeRef excInfoType = LLVMStructTypeInContext(ctx, excInfoTypes, 8, 0);
        Reference.reachabilityFence(excInfoTypes);

        LLVMValueRef excTypeInfo = LLVMAddGlobal(mod, excInfoType,
                new BytePointer("_ZNabunabu.exceptionsEtype_info"));
        LLVMSetLinkage(excTypeInfo, LLVMExternalLinkage);
        LLVMSetGlobalConstant(excTypeInfo, 1);

        PointerPointer<Pointer> excInfoFields = new PointerPointer<>(8);
        excInfoFields.put(0, excNameGlobal);
        excInfoFields.put(1, LLVMConstInt(i64, 0, 0));
        excInfoFields.put(2, LLVMConstNull(i8ptr));
        excInfoFields.put(3, LLVMConstInt(i32, 0, 0));
        excInfoFields.put(4, LLVMConstNull(i8ptr));
        excInfoFields.put(5, LLVMConstInt(i32, 0, 0));
        excInfoFields.put(6, LLVMConstNull(i8ptr));
        excInfoFields.put(7, LLVMConstInt(i32, 0, 0));
        LLVMValueRef excInit = LLVMConstNamedStruct(excInfoType, excInfoFields, 8);
        LLVMSetInitializer(excTypeInfo, excInit);
        Reference.reachabilityFence(excInit);
        globalValueMap.put("@nabu_exception_typeinfo", excTypeInfo);

        // --- Library/onbekende klassen: nabu_init_object ---
        // Voor klassen waarvan de backend geen layout kent (bv
        // java/lang/Exception) reserveert de backend zelf geheugen — met de
        // allocator van de gekozen strategie — en laat de runtime alleen de
        // object-header (type-info) vullen.
        // Signatuur: i8* nabu_init_object(i8* obj, i8* name).
        PointerPointer<Pointer> initObjParams = new PointerPointer<>(2);
        initObjParams.put(0, i8ptr);
        initObjParams.put(1, i8ptr);
        LLVMTypeRef initObjType = LLVMFunctionType(i8ptr, initObjParams, 2, 0);
        LLVMValueRef initObjFn = LLVMAddFunction(mod,
                new BytePointer("nabu_init_object"), initObjType);
        LLVMSetLinkage(initObjFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_init_object", initObjFn);

        // --- Class-literals: i8* nabu_class_literal(i8* name) ---
        // Een class-literal is een (geïnterneerd) Class-object van de runtime,
        // geen kale C-string met de naam: achter een Object-referentie krijgt
        // het virtuele aanroepen (equals), en die lezen een vtable uit de header.
        PointerPointer<Pointer> classLitParams = new PointerPointer<>(1);
        classLitParams.put(0, i8ptr);
        LLVMValueRef classLitFn = LLVMAddFunction(mod,
                new BytePointer("nabu_class_literal"),
                LLVMFunctionType(i8ptr, classLitParams, 1, 0));
        LLVMSetLinkage(classLitFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_class_literal", classLitFn);

        // --- void nabu_set_object_vtable(i8* vtable) ---
        PointerPointer<Pointer> setVtableParams = new PointerPointer<>(1);
        setVtableParams.put(0, i8ptr);
        LLVMValueRef setVtableFn = LLVMAddFunction(mod,
                new BytePointer("nabu_set_object_vtable"),
                LLVMFunctionType(LLVMVoidTypeInContext(ctx), setVtableParams, 1, 0));
        LLVMSetLinkage(setVtableFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_set_object_vtable", setVtableFn);

        // --- void nabu_set_object_itable(i8* itable) ---
        PointerPointer<Pointer> setItableParams = new PointerPointer<>(1);
        setItableParams.put(0, i8ptr);
        LLVMValueRef setItableFn = LLVMAddFunction(mod,
                new BytePointer("nabu_set_object_itable"),
                LLVMFunctionType(LLVMVoidTypeInContext(ctx), setItableParams, 1, 0));
        LLVMSetLinkage(setItableFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_set_object_itable", setItableFn);

        // --- void nabu_set_throwable_message_fn(i8* fn) ---
        PointerPointer<Pointer> setMsgParams = new PointerPointer<>(1);
        setMsgParams.put(0, i8ptr);
        LLVMValueRef setMsgFn = LLVMAddFunction(mod,
                new BytePointer("nabu_set_throwable_message_fn"),
                LLVMFunctionType(LLVMVoidTypeInContext(ctx), setMsgParams, 1, 0));
        LLVMSetLinkage(setMsgFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_set_throwable_message_fn", setMsgFn);

        // --- void nabu_register_type_infos(i8* table, i32 count) ---
        PointerPointer<Pointer> regTypesParams = new PointerPointer<>(2);
        regTypesParams.put(0, i8ptr);
        regTypesParams.put(1, LLVMInt32TypeInContext(ctx));
        LLVMValueRef regTypesFn = LLVMAddFunction(mod,
                new BytePointer("nabu_register_type_infos"),
                LLVMFunctionType(LLVMVoidTypeInContext(ctx), regTypesParams, 2, 0));
        LLVMSetLinkage(regTypesFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_register_type_infos", regTypesFn);

        // --- GC-specifieke helpers ---
        switch (opts.gcStrategy()) {
            case BOEHM -> declareBoehmHelpers(i8ptr, i64);
            case REFCOUNT -> declareRefCountHelpers(i8ptr, i64);
            case NONE -> { /* geen extra helpers nodig */ }
        }
    }

    /**
     * Boehm GC helpers: GC_init() en GC_malloc(size_t) -> i8*.
     */
    private void declareBoehmHelpers(LLVMTypeRef i8ptr, LLVMTypeRef i64) {
        // void GC_init()
        LLVMTypeRef gcInitType = LLVMFunctionType(LLVMVoidTypeInContext(ctx),
                new PointerPointer<>(0), 0, 0);
        LLVMValueRef gcInit = LLVMAddFunction(mod,
                new BytePointer("GC_init"), gcInitType);
        LLVMSetLinkage(gcInit, LLVMExternalLinkage);
        globalValueMap.put("@GC_init", gcInit);

        // i8* GC_malloc(size_t)
        PointerPointer<Pointer> gcMallocParams = new PointerPointer<>(1);
        gcMallocParams.put(0, i64);
        LLVMTypeRef gcMallocType = LLVMFunctionType(i8ptr, gcMallocParams, 1, 0);
        LLVMValueRef gcMalloc = LLVMAddFunction(mod,
                new BytePointer("GC_malloc"), gcMallocType);
        LLVMSetLinkage(gcMalloc, LLVMExternalLinkage);
        globalValueMap.put("@GC_malloc", gcMalloc);
    }

    /**
     * BOEHM: zet de collector op vóórdat er ook maar één {@code GC_malloc}
     * gebeurt. Via {@code @llvm.global_ctors} — niet als eerste instructie van
     * {@code main} — zodat de init ook draait wanneer de module in een ander
     * programma gelinkt wordt. De thunk is nodig om de {@code void(void)}-vorm
     * van een constructor-entry expliciet te maken.
     */
    private void registerBoehmGcInit(final ModuleCtors ctors) {
        if (opts.gcStrategy() != CompileOptions.GcStrategy.BOEHM) {
            return;
        }
        final LLVMValueRef gcInit = globalValueMap.get("@GC_init");
        if (gcInit == null) {
            throw new IllegalStateException("Runtime helper @GC_init not defined");
        }

        final LLVMTypeRef fnType = LLVMFunctionType(LLVMVoidTypeInContext(ctx),
                new PointerPointer<>(0), 0, 0);
        final LLVMValueRef thunk = LLVMAddFunction(mod,
                new BytePointer("nabu_gc_init_thunk"), fnType);
        LLVMSetLinkage(thunk, LLVMExternalLinkage);

        final LLVMBasicBlockRef block = LLVMAppendBasicBlock(thunk, "entry");
        final LLVMBuilderRef builder = LLVMCreateBuilderInContext(ctx);
        LLVMPositionBuilderAtEnd(builder, block);
        LLVMBuildCall2(builder, LLVMGlobalGetValueType(gcInit), gcInit,
                new PointerPointer<>(0), 0, "");
        LLVMBuildRetVoid(builder);
        LLVMDisposeBuilder(builder);

        ctors.add(thunk);
    }

    /**
     * Geeft de runtime de vtable van java.lang.Object en de library-itable, voor
     * de header van library-objecten (zie nabu_set_object_vtable/-itable). Vroeg
     * geregistreerd: een {@code <clinit>} kan al library-objecten aanmaken, en
     * die houden de tabellen die op dat moment bekend zijn.
     */
    private void registerObjectVtable(final ModuleCtors ctors) {
        final ClassLayouts.ClassLayout object = classLayouts.get("java/lang/Object");
        final LLVMValueRef vtable = object != null ? object.vtableGlobal : null;
        final LLVMValueRef itable = classLayouts.libraryItable();
        final LLVMValueRef getMessage = throwableGetMessage();
        // Alleen nodig als het programma Class.getSuperclass() aanroept (de
        // runtime zoekt dan de klasse op naam op).
        final LLVMValueRef superclassFn = LLVMGetNamedFunction(mod, "java.lang.Class_getSuperclass");
        // Ook voor Unsafe.objectFieldOffset: de runtime zoekt de veldoffset in
        // de type-info van de klasse met die naam.
        final LLVMValueRef fieldOffsetFn = LLVMGetNamedFunction(mod, "jdk.internal.misc.Unsafe_objectFieldOffset1");
        // En voor Class.forName: die kent alleen klassen uit de type-tabel.
        final LLVMValueRef forNameFn = LLVMGetNamedFunction(mod, "java.lang.Class_forName");
        final boolean needsTypeTable = (superclassFn != null && !superclassFn.isNull())
                || (fieldOffsetFn != null && !fieldOffsetFn.isNull())
                || (forNameFn != null && !forNameFn.isNull());
        final LLVMValueRef[] typeTable = needsTypeTable ? classLayouts.buildTypeInfoTable(mod) : null;
        final LLVMValueRef[] boxFns = compiledBoxFunctions();
        final boolean anyBoxFn = java.util.Arrays.stream(boxFns).anyMatch(java.util.Objects::nonNull);
        if (vtable == null && itable == null && getMessage == null && typeTable == null && !anyBoxFn) {
            return;
        }

        final LLVMTypeRef fnType = LLVMFunctionType(LLVMVoidTypeInContext(ctx),
                new PointerPointer<>(0), 0, 0);
        final LLVMValueRef thunk = LLVMAddFunction(mod,
                new BytePointer("nabu_object_vtable_init"), fnType);
        LLVMSetLinkage(thunk, LLVMInternalLinkage);

        final LLVMBasicBlockRef block = LLVMAppendBasicBlock(thunk, "entry");
        final LLVMBuilderRef builder = LLVMCreateBuilderInContext(ctx);
        LLVMPositionBuilderAtEnd(builder, block);
        if (vtable != null) {
            callSetter(builder, "@nabu_set_object_vtable", vtable);
            final int toStringSlot = object.toStringSlot();
            if (toStringSlot >= 0) {
                final LLVMValueRef setter = globalValueMap.get("@nabu_set_object_tostring_slot");
                final PointerPointer<Pointer> args = new PointerPointer<>(1);
                args.put(0, LLVMConstInt(LLVMInt32TypeInContext(ctx), toStringSlot, 0));
                LLVMBuildCall2(builder, LLVMGlobalGetValueType(setter), setter, args, 1, "");
                Reference.reachabilityFence(args);
            }
        }
        if (itable != null) {
            callSetter(builder, "@nabu_set_object_itable", itable);
        }
        if (getMessage != null) {
            callSetter(builder, "@nabu_set_throwable_message_fn", getMessage);
        }
        for (int kind = 0; kind < boxFns.length; kind++) {
            if (boxFns[kind] == null) continue;
            final LLVMValueRef setter = globalValueMap.get("@nabu_set_box_fn");
            final PointerPointer<Pointer> args = new PointerPointer<>(2);
            args.put(0, LLVMConstInt(LLVMInt32TypeInContext(ctx), kind, 0));
            args.put(1, boxFns[kind]);
            LLVMBuildCall2(builder, LLVMGlobalGetValueType(setter), setter, args, 2, "");
            Reference.reachabilityFence(args);
        }
        if (typeTable != null) {
            final LLVMValueRef register = globalValueMap.get("@nabu_register_type_infos");
            final PointerPointer<Pointer> args = new PointerPointer<>(2);
            args.put(0, typeTable[0]);
            args.put(1, typeTable[1]);
            LLVMBuildCall2(builder, LLVMGlobalGetValueType(register), register, args, 2, "");
            Reference.reachabilityFence(args);
        }
        LLVMBuildRetVoid(builder);
        LLVMDisposeBuilder(builder);

        ctors.addEarly(thunk);
    }

    /**
     * De uit bytecode gecompileerde {@code valueOf(primitief)} per wrapper, in
     * de volgorde van nabu_set_box_fn (Integer, Long, Boolean, Double, Float,
     * Short, Byte, Character); null waar het programma die niet bevat. Overloads
     * delen de IR-naam, dus de variant met precies die ene primitieve parameter.
     */
    private LLVMValueRef[] compiledBoxFunctions() {
        final String[] owners = {"Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte", "Character"};
        final String[] keys = {"I32", "I64", "Z", "F64", "F32", "I16", "I8", "I16"};
        final LLVMTypeRef[] params = {
                LLVMInt32TypeInContext(ctx), LLVMInt64TypeInContext(ctx), LLVMInt1TypeInContext(ctx),
                LLVMDoubleTypeInContext(ctx), LLVMFloatTypeInContext(ctx), LLVMInt16TypeInContext(ctx),
                LLVMInt8TypeInContext(ctx), LLVMInt16TypeInContext(ctx)};
        final IRType[] irParams = {
                new IRType.Int(32), new IRType.Int(64), new IRType.Bool(), new IRType.Float(64),
                new IRType.Float(32), IRType.I16, IRType.I8, IRType.CHAR};
        final LLVMValueRef[] result = new LLVMValueRef[owners.length];
        for (int kind = 0; kind < owners.length; kind++) {
            final String base = "java.lang." + owners[kind] + "_valueOf";
            // De bodies worden pas later geëmit; de IR zegt of er een is.
            if (!classLayouts.hasDefinedFunction(base, irParams[kind])) continue;
            for (final String name : new String[] {base, base + "$" + keys[kind]}) {
                final LLVMValueRef fn = LLVMGetNamedFunction(mod, name);
                if (fn != null && !fn.isNull()
                        && LLVMCountParams(fn) == 1
                        && LLVMTypeOf(LLVMGetParam(fn, 0)).equals(params[kind])) {
                    result[kind] = fn;
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Het gecompileerde {@code Throwable.getMessage()} (uit JDK-bytecode), zodat
     * de runtime bij een onbehandelde exception de melding kan tonen; null als
     * het programma die methode niet met een body bevat.
     */
    private LLVMValueRef throwableGetMessage() {
        final IRFunction fn = classLayouts.resolveFunction("java.lang.Throwable_getMessage");
        if (fn == null || fn.blocks().isEmpty() || fn.params.size() != 1) {
            return null;
        }
        final LLVMValueRef value = LLVMGetNamedFunction(mod, "java.lang.Throwable_getMessage");
        return value != null && !value.isNull() ? value : null;
    }

    private void callSetter(final LLVMBuilderRef builder, final String name, final LLVMValueRef table) {
        final LLVMValueRef setter = globalValueMap.get(name);
        final PointerPointer<Pointer> args = new PointerPointer<>(1);
        args.put(0, table);
        LLVMBuildCall2(builder, LLVMGlobalGetValueType(setter), setter, args, 1, "");
        Reference.reachabilityFence(args);
    }

    /**
     * Reference counting helpers: nabu_retain() en nabu_release().
     * Plus malloc/free voor initiële allocatie.
     */
    private void declareRefCountHelpers(LLVMTypeRef i8ptr, LLVMTypeRef i64) {
        // i8* malloc(size_t)
        PointerPointer<Pointer> mallocParams = new PointerPointer<>(1);
        mallocParams.put(0, i64);
        LLVMTypeRef mallocType = LLVMFunctionType(i8ptr, mallocParams, 1, 0);
        LLVMValueRef mallocFn = LLVMAddFunction(mod,
                new BytePointer("malloc"), mallocType);
        LLVMSetLinkage(mallocFn, LLVMExternalLinkage);
        globalValueMap.put("@malloc", mallocFn);

        // void free(i8*)
        PointerPointer<Pointer> freeParams = new PointerPointer<>(1);
        freeParams.put(0, i8ptr);
        LLVMTypeRef freeType = LLVMFunctionType(LLVMVoidTypeInContext(ctx), freeParams, 1, 0);
        LLVMValueRef freeFn = LLVMAddFunction(mod,
                new BytePointer("free"), freeType);
        LLVMSetLinkage(freeFn, LLVMExternalLinkage);
        globalValueMap.put("@free", freeFn);

        // void nabu_retain(i8*)
        PointerPointer<Pointer> retainParams = new PointerPointer<>(1);
        retainParams.put(0, i8ptr);
        LLVMTypeRef retainType = LLVMFunctionType(LLVMVoidTypeInContext(ctx), retainParams, 1, 0);
        LLVMValueRef retainFn = LLVMAddFunction(mod,
                new BytePointer("nabu_retain"), retainType);
        LLVMSetLinkage(retainFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_retain", retainFn);

        // void nabu_release(i8*)
        PointerPointer<Pointer> releaseParams = new PointerPointer<>(1);
        releaseParams.put(0, i8ptr);
        LLVMTypeRef releaseType = LLVMFunctionType(LLVMVoidTypeInContext(ctx), releaseParams, 1, 0);
        LLVMValueRef releaseFn = LLVMAddFunction(mod,
                new BytePointer("nabu_release"), releaseType);
        LLVMSetLinkage(releaseFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_release", releaseFn);
    }

    /**
     * Geeft de globalValueMap terug — voor tests en debuggen.
     */
    public Map<String, LLVMValueRef> globalValueMap() {
        return java.util.Collections.unmodifiableMap(globalValueMap);
    }
}

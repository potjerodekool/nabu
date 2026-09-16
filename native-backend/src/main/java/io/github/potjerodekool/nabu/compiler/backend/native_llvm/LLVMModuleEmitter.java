package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
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
    private io.github.potjerodekool.nabu.compiler.backend.native_llvm.config.ReflectionRegistry reflectionRegistry;

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
                ctx, mod, builder, types, constants, instructions, globalValueMap, blockMap);
    }

public void setReflectionRegistry(
            final io.github.potjerodekool.nabu.compiler.backend.native_llvm.config.ReflectionRegistry reflectionRegistry) {
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

        // 1. Globals (string-literals, variabelen) — vóór functies
        for (var module : modules)
            for (var global : module.globals().values())
                globals.emit(global);

        // 2. Alle signaturen — vóór bodies (wederzijdse recursie)
        //    Ook nodig vóór de vtable-opbouw, die functiepointers gebruikt.
        for (var module : modules)
            for (IRFunction fn : module.functions())
                functions.declareSignature(fn);

        // 3. Objectmodel (struct-layouts + type-info + vtable/itable) — na
        //    signaturen. Ouders vóór kinderen binnen één registerAll.
        this.classLayouts.registerAll(mod, modules);

        // 3.5 Reflectie-metadata (reflect-config-geregistreerde klassen):
        //     nabu_reflection_info-globals + @llvm.global_ctors-registratie.
        new ReflectionInfoEmitter(ctx, mod, globalValueMap)
                .emit(mod, this.classLayouts, modules, reflectionRegistry);

        // 4. Bodies
        for (var module : modules)
            for (IRFunction fn : module.functions())
                functions.emitBody(fn);
    }

    /**
     * Declareert runtime helpers als externe functies.
     * Gebaseerd op de gekozen GC-strategie.
     */
    private void declareRuntimeHelpers() {
        LLVMTypeRef i8ptr = LLVMPointerTypeInContext(ctx, 0);
        LLVMTypeRef i64   = LLVMInt64TypeInContext(ctx);

        // --- InstanceOf (altijd nodig) ---
        PointerPointer<Pointer> instanceofParams = new PointerPointer<>(2);
        instanceofParams.put(0, i8ptr);
        instanceofParams.put(1, i8ptr);
        LLVMTypeRef instanceofFnType = LLVMFunctionType(i8ptr, instanceofParams, 2, 0);
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

        // struct nabu_type_info { i8* name, i64 size, i8* super_type, i32 field_count }
        LLVMTypeRef i32 = LLVMInt32TypeInContext(ctx);
        PointerPointer<Pointer> excInfoTypes = new PointerPointer<>(4);
        excInfoTypes.put(0, i8ptr);
        excInfoTypes.put(1, i64);
        excInfoTypes.put(2, i8ptr);
        excInfoTypes.put(3, i32);
        LLVMTypeRef excInfoType = LLVMStructTypeInContext(ctx, excInfoTypes, 4, 0);
        Reference.reachabilityFence(excInfoTypes);

        LLVMValueRef excTypeInfo = LLVMAddGlobal(mod, excInfoType,
                new BytePointer("_ZNabunabu.exceptionsEtype_info"));
        LLVMSetLinkage(excTypeInfo, LLVMExternalLinkage);
        LLVMSetGlobalConstant(excTypeInfo, 1);

        PointerPointer<Pointer> excInfoFields = new PointerPointer<>(4);
        excInfoFields.put(0, excNameGlobal);
        excInfoFields.put(1, LLVMConstInt(i64, 0, 0));
        excInfoFields.put(2, LLVMConstNull(i8ptr));
        excInfoFields.put(3, LLVMConstInt(i32, 0, 0));
        LLVMValueRef excInit = LLVMConstNamedStruct(excInfoType, excInfoFields, 4);
        LLVMSetInitializer(excTypeInfo, excInit);
        Reference.reachabilityFence(excInit);
        globalValueMap.put("@nabu_exception_typeinfo", excTypeInfo);

        // --- Library/onbekende klassen allocatie: nabu_new_object ---
        // Voor klassen waarvan de backend geen layout kent (bv
        // java/lang/Exception) alloceert de runtime het object en zet de
        // object-header (type-info). Signatuur: i8* nabu_new_object(i8* name, i64 size).
        PointerPointer<Pointer> newObjParams = new PointerPointer<>(2);
        newObjParams.put(0, i8ptr);
        newObjParams.put(1, i64);
        LLVMTypeRef newObjType = LLVMFunctionType(i8ptr, newObjParams, 2, 0);
        LLVMValueRef newObjFn = LLVMAddFunction(mod,
                new BytePointer("nabu_new_object"), newObjType);
        LLVMSetLinkage(newObjFn, LLVMExternalLinkage);
        globalValueMap.put("@nabu_new_object", newObjFn);

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

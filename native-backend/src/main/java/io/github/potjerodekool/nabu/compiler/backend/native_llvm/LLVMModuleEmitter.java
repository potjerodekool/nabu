package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.backend.CompileOptions;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.IRModule;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;

import java.util.HashMap;
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
    private final InstructionEmitter instructions;
    private final FunctionEmitter functions;

    private final CompileOptions opts;

    public LLVMModuleEmitter(LLVMContextRef ctx,
                             LLVMModuleRef mod,
                             LLVMBuilderRef builder,
                             CompileOptions opts) {
        this.ctx   = ctx;
        this.mod   = mod;
        this.opts  = opts;

        this.types       = new TypeMapper(ctx);
        this.constants   = new ConstantResolver(ctx, mod, types, globalValueMap);
        this.globals     = new GlobalEmitter(mod, types, constants, globalValueMap);
        this.instructions = new InstructionEmitter(
                builder, ctx, types, constants, globalValueMap, blockMap, opts);
        this.functions   = new FunctionEmitter(
                ctx, mod, builder, types, instructions, globalValueMap, blockMap);
    }

    /**
     * Vertaalt de volledige IRModule naar LLVM IR.
     */
    public void emit(IRModule module) {
        // 0. Runtime helpers declareren
        declareRuntimeHelpers();

        // 1. Globals (string-literals, variabelen) — vóór functies
        for (var global : module.globals().values())
            globals.emit(global);

        // 2. Alle signaturen — vóór bodies (wederzijdse recursie)
        for (IRFunction fn : module.functions())
            functions.declareSignature(fn);

        // 3. Bodies
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

        // --- Personality functie voor exception handling ---
        // i32 @__gcc_personality_v0(...)
        LLVMTypeRef personalityFnType = LLVMFunctionType(LLVMInt32TypeInContext(ctx),
                new PointerPointer<>(0), 0, 1);
        LLVMValueRef personalityFn = LLVMAddFunction(mod,
                new BytePointer("__gcc_personality_v0"), personalityFnType);
        LLVMSetLinkage(personalityFn, LLVMExternalLinkage);
        globalValueMap.put("@__gcc_personality_v0", personalityFn);

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

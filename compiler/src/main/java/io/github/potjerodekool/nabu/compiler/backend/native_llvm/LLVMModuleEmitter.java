package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.ir.IRFunction;
import io.github.potjerodekool.nabu.ir.IRModule;
import org.bytedeco.llvm.LLVM.*;

import java.util.HashMap;
import java.util.Map;

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

    public LLVMModuleEmitter(LLVMContextRef ctx,
                             LLVMModuleRef mod,
                             LLVMBuilderRef builder,
                             CompileOptions opts) {
        this.ctx   = ctx;
        this.mod   = mod;

        this.types       = new TypeMapper(ctx);
        this.constants   = new ConstantResolver(ctx, mod, types, globalValueMap);
        this.globals     = new GlobalEmitter(mod, types, constants, globalValueMap);
        this.instructions = new InstructionEmitter(
                builder, ctx, types, constants, globalValueMap, blockMap);
        this.functions   = new FunctionEmitter(
                ctx, mod, builder, types, instructions, globalValueMap, blockMap);
    }

    /**
     * Vertaalt de volledige IRModule naar LLVM IR.
     */
    public void emit(IRModule module) {
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
     * Geeft de globalValueMap terug — voor tests en debuggen.
     */
    public Map<String, LLVMValueRef> globalValueMap() {
        return java.util.Collections.unmodifiableMap(globalValueMap);
    }
}

package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.ir.IRFunction;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;

import java.lang.ref.Reference;
import java.util.HashMap;
import java.util.Map;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Emitteert IRFunction objecten naar LLVM functie-bodies.
 *
 * Verantwoordelijkheden:
 *   - Functie-signatuur declareren in de module
 *   - Parameters koppelen aan lokale waarden
 *   - Basisblokken aanmaken (voor vooruitverwijzingen)
 *   - Instructies emitteren via InstructionEmitter
 *
 * Externe functies (isExternal() == true) krijgen alleen een declaratie,
 * geen body.
 */
public class FunctionEmitter {

    private final LLVMContextRef              ctx;
    private final LLVMModuleRef               mod;
    private final LLVMBuilderRef              builder;
    private final TypeMapper types;
    private final InstructionEmitter instructions;
    private final Map<String, LLVMValueRef>   globalValueMap;
    private final Map<String, LLVMBasicBlockRef> blockMap;

    public FunctionEmitter(LLVMContextRef ctx,
                           LLVMModuleRef mod,
                           LLVMBuilderRef builder,
                           TypeMapper types,
                           InstructionEmitter instructions,
                           Map<String, LLVMValueRef> globalValueMap,
                           Map<String, LLVMBasicBlockRef> blockMap) {
        this.ctx            = ctx;
        this.mod            = mod;
        this.builder        = builder;
        this.types          = types;
        this.instructions   = instructions;
        this.globalValueMap = globalValueMap;
        this.blockMap       = blockMap;
    }

    // -------------------------------------------------------
    // Signatuur declareren (stap 1 — vóór bodies)
    // -------------------------------------------------------

    /**
     * Declareert de functie-signatuur in de LLVM-module.
     * Mag worden aangeroepen voor wederzijds-recursieve functies.
     */
    public void declareSignature(IRFunction fn) {
        LLVMTypeRef[] paramArray = types.mapParams(fn.params);
        LLVMTypeRef   retType    = types.map(fn.returnType);

        PointerPointer<Pointer> paramTypes = types.toPointerPointer(paramArray);
        LLVMTypeRef   fnType  = LLVMFunctionType(retType, paramTypes,
                                    fn.params.size(), 0);
        LLVMValueRef  llvmFn  = LLVMAddFunction(mod,
                                    new BytePointer(fn.name), fnType);

        Reference.reachabilityFence(paramTypes);
        Reference.reachabilityFence(paramArray);

        if (llvmFn == null || llvmFn.isNull())
            throw new IllegalStateException(
                "LLVMAddFunction mislukt voor: " + fn.name);

        // Externe functies: ExternalLinkage, geen body
        if (fn.isExternal())
            LLVMSetLinkage(llvmFn, LLVMExternalLinkage);

        globalValueMap.put("@" + fn.name, llvmFn);
    }

    // -------------------------------------------------------
    // Body emitteren (stap 2)
    // -------------------------------------------------------

    /**
     * Emitteert de functie-body.
     * Externe functies worden overgeslagen.
     */
    public void emitBody(IRFunction fn) {
        if (fn.isExternal()) return;

        LLVMValueRef llvmFn = globalValueMap.get("@" + fn.name);
        if (llvmFn == null || llvmFn.isNull())
            throw new IllegalStateException(
                "Functie niet gedeclareerd: " + fn.name);

        // Schone lokale staat per functie
        Map<String, LLVMValueRef> localMap = new HashMap<>();
        blockMap.clear();
        instructions.setLocalValueMap(localMap);

        // Parameters koppelen
        for (int i = 0; i < fn.params.size(); i++) {
            LLVMValueRef param     = LLVMGetParam(llvmFn, i);
            String       paramName = IRValue.nameOf(fn.params.get(i));
            LLVMSetValueName2(param,
                new BytePointer(paramName), paramName.length());
            localMap.put(paramName, param);
        }

        // Alle basisblokken aanmaken (voor vooruitverwijzingen in branches)
        for (IRBasicBlock block : fn.blocks()) {
            LLVMBasicBlockRef bb = LLVMAppendBasicBlockInContext(ctx, llvmFn,
                    new BytePointer(block.label()));
            blockMap.put(block.label(), bb);
        }

        // Instructies emitteren per blok
        for (IRBasicBlock block : fn.blocks()) {
            LLVMPositionBuilderAtEnd(builder, blockMap.get(block.label()));
            for (var instr : block.instructions())
                instructions.emit(instr);
        }
    }
}

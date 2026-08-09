package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;

import java.lang.ref.Reference;
import java.util.*;

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

    /**
     * Maps a block label to its exception handler label if the block
     * is inside a try region. Built during emitBody().
     */
    private final Map<String, String> tryHandlerMap = new HashMap<>();

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

    /**
     * Retourneert de handler-label voor een blok als het in een try-regio zit,
     * of {@code null} als het blok niet beschermd is.
     * Wordt aangeroepen door InstructionEmitter om te beslissen of een
     * {@code call} moet worden omgezet naar {@code invoke}.
     */
    public String getHandlerLabel(String blockLabel) {
        return tryHandlerMap.get(blockLabel);
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
        tryHandlerMap.clear();
        instructions.setLocalValueMap(localMap);
        instructions.setFunctionEmitter(this);

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

        // Pre-scan: TryCatchRegion metadata → tryHandlerMap vullen
        // Voor elk try-blok tussen tryStart (inclusief) en tryEnd (exclusief)
        // geldt dat het beschermd is door handlerLabel.
        List<String> blockOrder = fn.blocks().stream()
                .map(IRBasicBlock::label)
                .toList();
        Set<String> handlerLabels = new HashSet<>();
        for (IRBasicBlock block : fn.blocks()) {
            for (var instr : block.instructions()) {
                if (instr instanceof IRInstruction.TryCatchRegion tc) {
                    handlerLabels.add(tc.handlerLabel());
                    int startIdx = blockOrder.indexOf(tc.tryStartLabel());
                    int endIdx   = blockOrder.indexOf(tc.tryEndLabel());
                    if (startIdx >= 0 && endIdx >= 0) {
                        for (int i = startIdx; i < endIdx; i++) {
                            tryHandlerMap.put(blockOrder.get(i), tc.handlerLabel());
                        }
                    }
                }
            }
        }

        // Personality functie instellen als er try-regio's zijn
        if (!handlerLabels.isEmpty()) {
            LLVMValueRef personality = globalValueMap.get("@__gcc_personality_v0");
            if (personality != null && !personality.isNull()) {
                LLVMSetPersonalityFn(llvmFn, personality);
            }
        }

        // Instructies emitteren per blok
        for (IRBasicBlock block : fn.blocks()) {
            LLVMPositionBuilderAtEnd(builder, blockMap.get(block.label()));

            // Catch handler blokken krijgen een landingpad
            if (handlerLabels.contains(block.label())) {
                emitLandingPad(block.label(), fn);
            }

            for (var instr : block.instructions()) {
                if (instr instanceof IRInstruction.TryCatchRegion) continue;
                instructions.emit(instr);
            }
        }
    }

    /**
     * Emitteert een landingpad voor een catch-handler blok.
     * Een landingpad is het LLVM-mechanisme om exceptions op te vangen.
     */
    private void emitLandingPad(String handlerLabel, IRFunction fn) {
        // Zoek de exception-type voor deze handler
        String exType = null;
        for (IRBasicBlock block : fn.blocks()) {
            if (block.label().equals(handlerLabel)) {
                for (var instr : block.instructions()) {
                    if (instr instanceof IRInstruction.TryCatchRegion tc
                            && tc.handlerLabel().equals(handlerLabel)) {
                        exType = tc.exceptionType();
                        break;
                    }
                }
                break;
            }
        }

        // %landing_pad = landingpad { ptr, i32 }
        //     catch ptr @_ZTIPKc    (catch-all)
        //     catch ptr @exceptionType  (specifiek type)
        LLVMTypeRef i8Ptr = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);
        LLVMTypeRef lpType = LLVMStructTypeInContext(ctx,
                new PointerPointer<>(new LLVMTypeRef[]{i8Ptr, LLVMInt32TypeInContext(ctx)}),
                2, 0);

        // Bepaal hoeveel clauses we nodig hebben
        List<String> clauses = new ArrayList<>();
        if (exType != null) {
            clauses.add(exType);
        }
        // Altijd een catch-all clause toevoegen (fallback)
        clauses.add("catch-all");

        LLVMValueRef landingPad = LLVMBuildLandingPad(builder, lpType,
                globalValueMap.getOrDefault("@__gcc_personality_v0",
                        LLVMConstNull(LLVMPointerType(LLVMInt8TypeInContext(ctx), 0))),
                clauses.size(),
                new BytePointer("lpad"));

        // Voeg clauses toe
        for (String clause : clauses) {
            if (clause.equals("catch-all")) {
                // catch-all: vang alle exceptions
                // Gebruik @llvm.eh.typeid.for om een unieke ID te krijgen
                // Voor nu: voeg een null clause toe (vangt alles)
                // In LLVM is een landingpad zonder clauses een cleanup pad
            } else {
                // Specifiek type: voeg catch clause toe
                LLVMValueRef typeInfo = resolveTypeInfo(clause);
                if (typeInfo != null) {
                    LLVMAddClause(landingPad, typeInfo);
                }
            }
        }

        // Extracteer de raw exception pointer van de landingpad
        String rawExnName = "%raw_exn." + handlerLabel;
        LLVMValueRef rawExPtr = LLVMBuildExtractValue(builder, landingPad, 0,
                new BytePointer(rawExnName));

        // Roep nabu_catch aan om de Nabu exception object pointer te krijgen
        // nabu_catch(void* unwind_exc) -> void*
        LLVMValueRef catchFn = globalValueMap.get("@nabu_catch");
        if (catchFn == null) {
            throw new IllegalStateException("Runtime helper @nabu_catch niet gedefinieerd");
        }
        String exnName = "%exn." + handlerLabel;
        LLVMTypeRef catchFnType = getNabuCatchType();
        PointerPointer<Pointer> catchArgs = new PointerPointer<>(1);
        catchArgs.put(0, rawExPtr);
        LLVMValueRef exPtr = LLVMBuildCall2(builder,
                catchFnType,
                catchFn,
                catchArgs,
                1,
                exnName);
        instructions.getLocalValueMap().put(exnName, exPtr);

        // Selector is nodig voor type-specifieke catch filters (voorlopig opslaan)
        String selName = "%ehselector." + handlerLabel;
        LLVMValueRef selector = LLVMBuildExtractValue(builder, landingPad, 1,
                new BytePointer(selName));
        instructions.getLocalValueMap().put(selName, selector);
    }

    /**
     * Retourneert het LLVM type voor de nabu_catch functie: i8* (i8*)
     */
    private LLVMTypeRef getNabuCatchType() {
        LLVMTypeRef i8Ptr = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);
        PointerPointer<Pointer> params = new PointerPointer<>(1);
        params.put(0, i8Ptr);
        return LLVMFunctionType(i8Ptr, params, 1, 0);
    }

    /**
     * Resoloveert een type-info referentie voor een exception-type.
     * Zoekt eerst in de module, en retourneert null als niet gevonden.
     */
    private LLVMValueRef resolveTypeInfo(String className) {
        // Zoek naar @_ZTI + className (Itanium ABI)
        LLVMValueRef typeInfo = globalValueMap.get("@_ZTI" + className);
        if (typeInfo != null) return typeInfo;

        // Fallback: maak een forward declaration aan
        LLVMTypeRef i8Ptr = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);
        LLVMTypeRef typeInfoType = LLVMStructTypeInContext(ctx,
                new PointerPointer<>(new LLVMTypeRef[]{
                        LLVMInt32TypeInContext(ctx), i8Ptr}), 2, 0);

        LLVMValueRef global = LLVMAddGlobal(mod, typeInfoType,
                new BytePointer("_ZTI" + className));
        LLVMSetLinkage(global, LLVMExternalLinkage);
        globalValueMap.put("@_ZTI" + className, global);
        return global;
    }
}

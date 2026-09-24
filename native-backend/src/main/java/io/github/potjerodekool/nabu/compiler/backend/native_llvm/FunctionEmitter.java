package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
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
    private final ConstantResolver constants;
    private final InstructionEmitter instructions;
    private final Map<String, LLVMValueRef>   globalValueMap;
    private final Map<String, LLVMBasicBlockRef> blockMap;

    /**
     * Maps a block label to its exception handler label if the block
     * is inside a try region. Built during emitBody().
     */
    private final Map<String, String> tryHandlerMap = new HashMap<>();

    /**
     * Maps een IR-blok-label van een catch-handler naar het (nieuwe) LLVM
     * blok waarin de catch-body-instructies worden geëmit. De aanloop-code
     * (landingpad + type-check + rethrow) wordt in het oorspronkelijke
     * handler-blok geëmit, de catch-body in dit aparte blok.
     */
    private final Map<String, LLVMBasicBlockRef> handlerBodyBlocks = new HashMap<>();

    public FunctionEmitter(LLVMContextRef ctx,
                           LLVMModuleRef mod,
                           LLVMBuilderRef builder,
                           TypeMapper types,
                           ConstantResolver constants,
                           InstructionEmitter instructions,
                           Map<String, LLVMValueRef> globalValueMap,
                           Map<String, LLVMBasicBlockRef> blockMap) {
        this.ctx            = ctx;
        this.mod            = mod;
        this.builder        = builder;
        this.types          = types;
        this.constants      = constants;
        this.instructions   = instructions;
        this.globalValueMap = globalValueMap;
        this.blockMap       = blockMap;
    }

    /**
     * Retourneert het LLVM-blok voor de catch-body van een handler (of null
     * als het blok geen handler is). Wordt gebruikt door emitBody om de
     * instructies van een catch-handler in het juiste blok te emitteren.
     */
    public LLVMBasicBlockRef getHandlerBodyBlock(String blockLabel) {
        return handlerBodyBlocks.get(blockLabel);
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
        final List<IRType> paramKey = fn.params.stream()
                .map(IRValue::type)
                .toList();
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
        // Overload-sleutel: dezelfde fn onder `<naam>#<params>` registreren
        // (Range.min()/Range.min(int) deelden voorheen één LLVM-signature,
        // waardoor GetParam(i) van de latere overload garbage opleverde).
        globalValueMap.put("@" + FnKeys.fnKey(fn.name, paramKey), llvmFn);
    }

    /**
     * Declareert een externe callee die als (ad-hoc) call in een IR-module
     * voorkomt zonder eigen IRFunction-declaratie (bv. interface-calls
     * java.util.Iterator_hasNext). De signatuur volgt de call-instructie.
     */
    public void declareSignature(IRInstruction.Call call) {
        final String fnName = call.function();

        LLVMTypeRef[] paramArray = new LLVMTypeRef[call.paramTypes().size()];
        for (int i = 0; i < paramArray.length; i++) {
            paramArray[i] = types.map(call.paramTypes().get(i));
        }
        LLVMTypeRef retType = types.map(call.returnType());

        PointerPointer<Pointer> paramTypes = types.toPointerPointer(paramArray);
        LLVMTypeRef fnType = LLVMFunctionType(retType, paramTypes,
                paramArray.length, 0);
        LLVMValueRef llvmFn = LLVMAddFunction(mod,
                new BytePointer(fnName), fnType);

        Reference.reachabilityFence(paramTypes);
        Reference.reachabilityFence(paramArray);

        if (llvmFn == null || llvmFn.isNull())
            throw new IllegalStateException(
                "LLVMAddFunction mislukt voor: " + fnName);

        LLVMSetLinkage(llvmFn, LLVMExternalLinkage);
        globalValueMap.put("@" + fnName, llvmFn);
        globalValueMap.put("@" + FnKeys.fnKey(fnName, call.paramTypes()), llvmFn);
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

        final List<IRType> paramKey = fn.params.stream()
                .map(IRValue::type)
                .toList();
        final var overloadKey = FnKeys.fnKey(fn.name, paramKey);
        LLVMValueRef llvmFn = globalValueMap.get("@" + overloadKey);
        if (llvmFn == null || llvmFn.isNull()) {
            llvmFn = globalValueMap.get("@" + fn.name);
        }
        if (llvmFn == null || llvmFn.isNull())
            throw new IllegalStateException(
                "Functie niet gedeclareerd: " + overloadKey);

        // Schone lokale staat per functie
        Map<String, LLVMValueRef> localMap = new HashMap<>();
        blockMap.clear();
        tryHandlerMap.clear();
        instructions.setLocalValueMap(localMap);
        instructions.setFunctionEmitter(this);

        // Parameters koppelen
        final int declaredParams = LLVMCountParams(llvmFn);
        if (declaredParams != fn.params.size()) {
            throw new IllegalStateException(
                    "Overload-naming-collision: " + overloadKey
                            + " declares " + declaredParams + " params, verwacht "
                            + fn.params.size());
        }
        for (int i = 0; i < fn.params.size(); i++) {
            LLVMValueRef param     = LLVMGetParam(llvmFn, i);
            String       paramName = IRValue.nameOf(fn.params.get(i));
            if (param == null || param.isNull()) {
                throw new IllegalStateException(
                        "LLVMGetParam(" + i + ") null voor " + fn.name
                                + " (declaredParamCount=" + declaredParams + ")");
            }
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

        // Phi-slot allocas: SSA-phi-artefacten (`%phi...`-temps) draaien via
        // gedeelde stackslots (wat het ASM-backend via slot-allocatie doet).
        // Zonder een alloca kan de Store/Load op zo'n temp niet resolven.
        Map<String, LLVMTypeRef> phiSlots = new HashMap<>();
        for (IRBasicBlock block : fn.blocks()) {
            for (var instr : block.instructions()) {
                collectPhiTemps(instr, phiSlots);
            }
        }
        if (!phiSlots.isEmpty()) {
            LLVMBasicBlockRef entryBlock = blockMap.get(fn.blocks().get(0).label());
            // Boven aan het entry-blok plaatsen (het entry kan al terminator
            // bevatten na optimizer-reductie; post-terminator-geinstrueer brengt
            // de LLVM-builder in native disarray).
            LLVMValueRef firstInstr = LLVMGetFirstInstruction(entryBlock);
            if (firstInstr != null && !firstInstr.isNull()) {
                LLVMPositionBuilder(builder, entryBlock, firstInstr);
            } else {
                LLVMPositionBuilderAtEnd(builder, entryBlock);
            }
            for (var entry : phiSlots.entrySet()) {
                LLVMValueRef slotPtr = LLVMBuildAlloca(
                        builder, entry.getValue(),
                        new BytePointer("." + entry.getKey()));
                localMap.put(entry.getKey(), slotPtr);
                instructions.setLocalValueMap(localMap);
            }
            if (firstInstr != null && !firstInstr.isNull()) {
                LLVMPositionBuilderAtEnd(builder, entryBlock);
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

            // Catch handler blokken krijgen een landingpad + type-check + rethrow,
            // en de catch-body wordt in een apart (body)blok geëmit.
            if (handlerLabels.contains(block.label())) {
                emitLandingPad(block.label(), fn);
                // emitLandingPad positioneert de builder aan het einde van het
                // body-blok, waarin de catch-body-instructies daarna worden geëmit.
            }

            for (var instr : block.instructions()) {
                if (instr instanceof IRInstruction.TryCatchRegion) continue;
                instructions.emit(instr);
            }
        }
    }

    /** Verzamelt alle `%phi...`-temps van een instructie met hun IRType. */
    private void collectPhiTemps(final IRInstruction instr,
                                 final Map<String, LLVMTypeRef> into) {
        switch (instr) {
            case IRInstruction.Store st -> addPhiValue(st.ptr(), into);
            case IRInstruction.Load ld -> addPhiValue(ld.ptr(), into);
            case IRInstruction.BinaryOp op -> {
                addPhiValue(op.left(), into);
                addPhiValue(op.right(), into);
            }
            case IRInstruction.Call call -> addPhiValues(call.args(), into);
            case IRInstruction.Cast c -> addPhiValue(c.source(), into);
            case IRInstruction.Phi phi -> {
                addPhiValue(phi.result(), into);
                for (var incoming : phi.incomingValues()) {
                    addPhiValue(incoming.value(), into);
                }
            }
            case IRInstruction.Move m -> { addPhiValue(m.value(), into); }
            case IRInstruction.Return r -> addPhiValue(r.value(), into);
            case IRInstruction.CondBranch cb -> addPhiValue(cb.condition(), into);
            default -> {
            }
        }
    }

    private void addPhiValue(final IRValue value,
                             final Map<String, LLVMTypeRef> into) {
        if (value == null) return;
        if (value instanceof IRValue.Temp t
                && t.name().startsWith("%phi")
                && !into.containsKey(t.name())
                && t.type() != null) {
            LLVMTypeRef slotType = types.map(t.type() instanceof IRType.Ptr p
                    ? p.pointee() : t.type());
            into.put(t.name(), slotType);
        }
    }

    private void addPhiValues(final List<? extends IRValue> values,
                              final Map<String, LLVMTypeRef> into) {
        if (values == null) return;
        for (var v : values) addPhiValue(v, into);
    }

    /**
     * Emitteert een landingpad voor een catch-handler blok, gevolgd door een
     * catch type-check (nabu_can_catch) en een rethrow-pad voor niet-matchende
     * exceptions.
     *
     * De gegenereerde structuur in het handler-blok:
     *   %handlerBlock:
     *     %lpad = landingpad { ptr, i32 } cleanup
     *     %raw   = extractvalue %lpad, 0
     *     %exn.<h> = call i8* @nabu_catch(ptr %raw)
     *     %match = call i32 @nabu_can_catch(ptr %exn.<h>, i8* @.str.<type>)
     *     %ok    = icmp ne i32 %match, 0
     *     br i1 %ok, label %handler.<h>.body, label %handler.<h>.rethrow
     *   %handler.<h>.body:            <- hierin wordt de catch-body geëmit
     *   %handler.<h>.rethrow:
     *     call void @nabu_throw(ptr %exn.<h>)
     *     unreachable
     *
     * @return het body-blok waarin de catch-body-instructies geëmit moeten worden.
     */
    private void emitLandingPad(String handlerLabel, IRFunction fn) {
        // Zoek het catch-type voor deze handler
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

        LLVMTypeRef i8Ptr = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);
        LLVMTypeRef i64   = LLVMInt64TypeInContext(ctx);
        LLVMTypeRef i32   = LLVMInt32TypeInContext(ctx);
        LLVMTypeRef lpType = LLVMStructTypeInContext(ctx,
                new PointerPointer<>(new LLVMTypeRef[]{i8Ptr, i32}), 2, 0);

        // Landingpad met een echte catch-clause zodat LLVM een LSDA
        // (.gcc_except_table) emitteert. Zonder een echte catch-clause zou LLVM
        // geen LSDA genereren en kan de nabu-persoonlijkheid
        // (nabu_seh_personality) het landingspad niet vinden.
        //
        // NB: de type-match gebeurt in code via nabu_can_catch, dus de clause
        // hoef hier geen specifiek type-info te zijn; elke gegarandeerd
        // aanwezige typeinfo-global dient als LSDA-trigger. Die wordt in
        // LLVMModuleEmitter als `@_ZNabunabu.exceptionsEtype_info` geboord.
        LLVMValueRef personality = globalValueMap.get("@__gcc_personality_v0");
        LLVMValueRef landingPad = LLVMBuildLandingPad(builder, lpType,
                personality != null ? personality : LLVMConstNull(i8Ptr),
                1, new BytePointer("lpad"));
        LLVMSetCleanup(landingPad, 1);

        LLVMValueRef excTypeInfo = globalValueMap.get("@nabu_exception_typeinfo");
        if (excTypeInfo != null) {
            LLVMAddClause(landingPad, excTypeInfo);
        } else {
            // Uiterste valback: geen clause (dan geen LSDA, maar geen crash).
            LLVMValueRef anyTypeInfo = globalValueMap.get("@_ZNabuMainEtype_info");
            if (anyTypeInfo != null) {
                LLVMAddClause(landingPad, anyTypeInfo);
            }
        }

        // Raw exception pointer extraheren en nabu_catch() -> nabu_object*
        LLVMValueRef rawExPtr = LLVMBuildExtractValue(builder, landingPad, 0,
                new BytePointer("%raw_exn." + handlerLabel));
        LLVMValueRef catchFn = globalValueMap.get("@nabu_catch");
        if (catchFn == null) {
            throw new IllegalStateException("Runtime helper @nabu_catch niet gedefinieerd");
        }
        String exnName = "%exn." + handlerLabel;
        LLVMTypeRef catchFnType = getNabuCatchType();
        PointerPointer<Pointer> catchArgs = new PointerPointer<>(1);
        catchArgs.put(0, rawExPtr);
        LLVMValueRef exPtr = LLVMBuildCall2(builder, catchFnType, catchFn, catchArgs, 1,
                exnName);
        instructions.getLocalValueMap().put(exnName, exPtr);

        // Bepaal de function-eigenaar voor de blokken
        LLVMValueRef currentFn = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder));

        LLVMBasicBlockRef bodyBlock = LLVMAppendBasicBlockInContext(ctx, currentFn,
                new BytePointer("handler." + handlerLabel + ".body"));

        if (exType == null) {
            // catch-all: geen type-check, direct naar de body.
            LLVMBuildBr(builder, bodyBlock);
        } else {
            // Type-naam-string + nabu_can_catch check; bij niet-matchende
            // exception gaat het pad langs het rethrow-blok.
            LLVMBasicBlockRef rethrowBlock = LLVMAppendBasicBlockInContext(ctx, currentFn,
                    new BytePointer("handler." + handlerLabel + ".rethrow"));

            String typeName = normalizeCatchType(exType);
            LLVMValueRef typeStr = constants.resolveString(typeName,
                    io.github.potjerodekool.nabu.backend.ir.IRGlobal.Linkage.PRIVATE);

            LLVMValueRef canCatchFn = globalValueMap.get("@nabu_can_catch");
            if (canCatchFn == null) {
                throw new IllegalStateException("Runtime helper @nabu_can_catch niet gedefinieerd");
            }
            PointerPointer<Pointer> ccParams = new PointerPointer<>(2);
            ccParams.put(0, exPtr);
            ccParams.put(1, typeStr);
            // NB: het param-type-buffer moet TYPE-refs bevatten (LLVMTypeOf van
            // de argumenten), niet de waarde-refs zelf — LLVMTypeRef en
            // LLVMValueRef zijn allebei llvm::Value*, en het doorgeven van
            // waarde-tips aan LLVMFunctionType geeft garbage-paramtypes die
            // LLVMs verifier laten crashen / "call parameter type does not
            // match" melden.
            PointerPointer<Pointer> ccParamTypes = new PointerPointer<>(2);
            ccParamTypes.put(0, LLVMTypeOf(exPtr));
            ccParamTypes.put(1, LLVMTypeOf(typeStr));
            LLVMTypeRef canCatchFnType = LLVMFunctionType(i32, ccParamTypes, 2, 0);
            LLVMValueRef match = LLVMBuildCall2(builder, canCatchFnType, canCatchFn,
                    ccParams, 2, "%catch.match");
            LLVMValueRef ok = LLVMBuildICmp(builder, LLVMIntNE, match,
                    LLVMConstInt(i32, 0, 0), "%catch.ok");
            LLVMBuildCondBr(builder, ok, bodyBlock, rethrowBlock);
            Reference.reachabilityFence(ccParams);
            Reference.reachabilityFence(ccParamTypes);

            // Rethrow-pad: niet-matchende exception opnieuw gooien
            LLVMPositionBuilderAtEnd(builder, rethrowBlock);
            LLVMValueRef throwFn = globalValueMap.get("@nabu_throw");
            if (throwFn == null) {
                throw new IllegalStateException("Runtime helper @nabu_throw niet gedefinieerd");
            }
            PointerPointer<Pointer> throwFnParams = new PointerPointer<>(1);
            throwFnParams.put(0, i8Ptr);
            LLVMTypeRef throwFnType = LLVMFunctionType(LLVMVoidTypeInContext(ctx),
                    throwFnParams, 1, 0);
            Reference.reachabilityFence(throwFnParams);
            PointerPointer<Pointer> throwArgs = new PointerPointer<>(1);
            throwArgs.put(0, exPtr);
            LLVMBuildCall2(builder, throwFnType, throwFn, throwArgs, 1, "");
            LLVMBuildUnreachable(builder);
            Reference.reachabilityFence(rethrowBlock);
        }

        // Builder positioneren in het body-blok voor de catch-body-instructies
        LLVMPositionBuilderAtEnd(builder, bodyBlock);
        handlerBodyBlocks.put(handlerLabel, bodyBlock);
        Reference.reachabilityFence(i64);
    }

    /** Normaliseert een catch-type naar de interne naam met slashes (test.Exception -> test/Exception). */
    private static String normalizeCatchType(String exType) {
        if (exType == null) return null;
        String s = ClassLayouts.normalizeInternalName(exType);
        if (s == null) return null;
        return s.replace('.', '/');
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
}

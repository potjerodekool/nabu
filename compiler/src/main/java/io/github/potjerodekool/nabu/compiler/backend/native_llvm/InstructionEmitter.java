package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.ir.IRGlobal;
import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;

import java.lang.ref.Reference;
import java.util.Map;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Vertaalt IRInstruction objecten naar LLVM builder-aanroepen.
 *
 * Ondersteunt:
 *   BinaryOp, Alloca, Load, Store,
 *   Call (intern + extern + indirect),
 *   Branch, CondBranch, Return, Cast
 *
 * Waarde-resolutie:
 *   - localValueMap  : tijdelijke registers + parameters (per functie)
 *   - globalValueMap : functies, string-globals, gedeclareerde globals
 */
public class InstructionEmitter {

    private final LLVMBuilderRef              builder;
    private final LLVMContextRef              ctx;
    private final TypeMapper types;
    private final ConstantResolver constants;
    private final Map<String, LLVMValueRef>   globalValueMap;
    private final Map<String, LLVMBasicBlockRef> blockMap;

    // Per-functie lokale registers
    private Map<String, LLVMValueRef> localValueMap;

    public InstructionEmitter(LLVMBuilderRef builder,
                              LLVMContextRef ctx,
                              TypeMapper types,
                              ConstantResolver constants,
                              Map<String, LLVMValueRef> globalValueMap,
                              Map<String, LLVMBasicBlockRef> blockMap) {
        this.builder        = builder;
        this.ctx            = ctx;
        this.types          = types;
        this.constants      = constants;
        this.globalValueMap = globalValueMap;
        this.blockMap       = blockMap;
    }

    public void setLocalValueMap(Map<String, LLVMValueRef> localValueMap) {
        this.localValueMap = localValueMap;
    }

    // -------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------

    public void emit(IRInstruction instr) {
        switch (instr) {
            case IRInstruction.BinaryOp   op -> emitBinaryOp(op);
            case IRInstruction.Alloca      a -> emitAlloca(a);
            case IRInstruction.Load     load -> emitLoad(load);
            case IRInstruction.Store   store -> emitStore(store);
            case IRInstruction.Call     call -> emitCall(call);
            case IRInstruction.IndirectCall ic -> emitIndirectCall(ic);
            case IRInstruction.Branch     br -> emitBranch(br);
            case IRInstruction.CondBranch  cb -> emitCondBranch(cb);
            case IRInstruction.Return    ret -> emitReturn(ret);
            case IRInstruction.Cast        c -> emitCast(c);
            default -> throw new UnsupportedOperationException(
                "Niet ondersteunde instructie: " + instr.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------
    // BinaryOp
    // -------------------------------------------------------

    private void emitBinaryOp(IRInstruction.BinaryOp op) {
        LLVMValueRef left  = resolveValue(op.left());
        LLVMValueRef right = resolveValue(op.right());
        BytePointer  name  = new BytePointer(nameOf(op.result()));

        LLVMValueRef result = switch (op.op()) {
            case ADD -> LLVMBuildAdd (builder, left, right, name);
            case SUB -> LLVMBuildSub (builder, left, right, name);
            case MUL -> LLVMBuildMul (builder, left, right, name);
            case DIV -> LLVMBuildSDiv(builder, left, right, name);
            case MOD -> LLVMBuildSRem(builder, left, right, name);
            case AND -> LLVMBuildAnd (builder, left, right, name);
            case OR  -> LLVMBuildOr  (builder, left, right, name);
            case XOR -> LLVMBuildXor (builder, left, right, name);
            case EQ  -> LLVMBuildICmp(builder, LLVMIntEQ,  left, right, name);
            case NEQ -> LLVMBuildICmp(builder, LLVMIntNE,  left, right, name);
            case LT  -> LLVMBuildICmp(builder, LLVMIntSLT, left, right, name);
            case LTE -> LLVMBuildICmp(builder, LLVMIntSLE, left, right, name);
            case GT  -> LLVMBuildICmp(builder, LLVMIntSGT, left, right, name);
            case GTE -> LLVMBuildICmp(builder, LLVMIntSGE, left, right, name);
        };
        storeLocal(op.result(), result);
    }

    // -------------------------------------------------------
    // Geheugen
    // -------------------------------------------------------

    private void emitAlloca(IRInstruction.Alloca a) {
        LLVMValueRef ptr = LLVMBuildAlloca(builder,
                types.map(a.allocType()),
                new BytePointer(nameOf(a.result())));
        storeLocal(a.result(), ptr);
    }

    private void emitLoad(IRInstruction.Load load) {
        IRType ptrType = load.ptr().type();

        // Defensieve check: ptr moet een pointer-type zijn
        if (!(ptrType instanceof IRType.Ptr ptr)) {
            throw new IllegalStateException(
                    "emitLoad verwacht een pointer-type, maar kreeg: "
                            + ptrType + " voor waarde: " + nameOf(load.ptr())
                            + "\nTip: parameters zijn geen pointers — gebruik ze direct "
                            + "of sla ze op via emitAlloca + emitStore.");
        }

        LLVMValueRef llvmPtr = resolveValue(load.ptr());
        LLVMValueRef val     = LLVMBuildLoad2(builder,
                types.map(ptr.pointee()), llvmPtr,
                new BytePointer(nameOf(load.result())));
        storeLocal(load.result(), val);
    }

    private void emitStore(IRInstruction.Store store) {
        LLVMBuildStore(builder,
                resolveValue(store.value()),
                resolveValue(store.ptr()));
    }

    // -------------------------------------------------------
    // Aanroepen
    // -------------------------------------------------------

    private void emitCall(IRInstruction.Call call) {
        LLVMValueRef fn = globalValueMap.get("@" + call.function());
        if (fn == null)
            throw new IllegalStateException(
                "Onbekende functie: " + call.function());

        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);
        PointerPointer<Pointer> args = buildArgs(call.args());

        String       name   = call.result() != null ? nameOf(call.result()) : "";
        LLVMValueRef result = LLVMBuildCall2(builder, fnType, fn,
                args, call.args().size(), name);

        Reference.reachabilityFence(args);

        if (call.result() != null)
            storeLocal(call.result(), result);
    }

    private void emitIndirectCall(IRInstruction.IndirectCall ic) {
        LLVMValueRef callee    = resolveValue(ic.callee());
        LLVMTypeRef  fnType    = types.map(ic.fnType());
        PointerPointer<Pointer> args = buildArgs(ic.args());

        String       name   = ic.result() != null ? nameOf(ic.result()) : "";
        LLVMValueRef result = LLVMBuildCall2(builder, fnType, callee,
                args, ic.args().size(), name);

        Reference.reachabilityFence(args);

        if (ic.result() != null)
            storeLocal(ic.result(), result);
    }

    // -------------------------------------------------------
    // Controle-stroom
    // -------------------------------------------------------

    private void emitBranch(IRInstruction.Branch br) {
        LLVMBasicBlockRef target = blockMap.get(br.targetLabel());
        if (target == null)
            throw new IllegalStateException("Onbekend label: " + br.targetLabel());
        LLVMBuildBr(builder, target);
    }

    private void emitCondBranch(IRInstruction.CondBranch cb) {
        LLVMBasicBlockRef trueBlock  = blockMap.get(cb.trueLabel());
        LLVMBasicBlockRef falseBlock = blockMap.get(cb.falseLabel());
        if (trueBlock  == null) throw new IllegalStateException("Onbekend true-label:  " + cb.trueLabel());
        if (falseBlock == null) throw new IllegalStateException("Onbekend false-label: " + cb.falseLabel());
        LLVMBuildCondBr(builder, resolveValue(cb.condition()), trueBlock, falseBlock);
    }

    private void emitReturn(IRInstruction.Return ret) {
        if (ret.value() == null)
            LLVMBuildRetVoid(builder);
        else
            LLVMBuildRet(builder, resolveValue(ret.value()));
    }

    // -------------------------------------------------------
    // Cast
    // -------------------------------------------------------

    private void emitCast(IRInstruction.Cast cast) {
        LLVMValueRef src    = resolveValue(cast.source());
        LLVMTypeRef  target = types.map(cast.targetType());
        BytePointer  name   = new BytePointer(nameOf(cast.result()));

        LLVMValueRef result = switch (cast.source().type()) {
            case IRType.Int si when cast.targetType() instanceof IRType.Int ti ->
                    si.bits() < ti.bits()
                        ? LLVMBuildSExt (builder, src, target, name)
                        : LLVMBuildTrunc(builder, src, target, name);
            case IRType.Int   __ when cast.targetType() instanceof IRType.Float ->
                    LLVMBuildSIToFP(builder, src, target, name);
            case IRType.Float __ when cast.targetType() instanceof IRType.Int ->
                    LLVMBuildFPToSI(builder, src, target, name);
            default ->
                    LLVMBuildBitCast(builder, src, target, name);
        };
        storeLocal(cast.result(), result);
    }

    // -------------------------------------------------------
    // Waarde-resolutie
    // -------------------------------------------------------

    /**
     * Resolveert een IRValue naar een LLVMValueRef.
     * Volgorde: lokaal → globaal → constante.
     */
    public LLVMValueRef resolveValue(IRValue value) {
        return switch (value) {
            case IRValue.Temp t -> {
                LLVMValueRef v = localValueMap.get(t.name());
                if (v == null) v = globalValueMap.get(t.name());
                if (v == null)
                    throw new IllegalStateException("Onbekend register: " + t.name());
                yield v;
            }
            case IRValue.Named n -> {
                LLVMValueRef v = globalValueMap.get(n.name());
                if (v == null)
                    throw new IllegalStateException("Onbekende global: " + n.name());
                yield v;
            }
            case IRValue.FunctionRef r -> {
                LLVMValueRef v = globalValueMap.get("@" + r.name());
                if (v == null)
                    throw new IllegalStateException("Onbekende functiereferentie: " + r.name());
                yield v;
            }
            case IRValue.ConstString c ->
                // Inline string — maak global aan via ConstantResolver
                constants.resolveString(c.value(), IRGlobal.Linkage.PRIVATE);

            default -> constants.resolve(value);
        };
    }

    // -------------------------------------------------------
    // Hulp
    // -------------------------------------------------------

    private PointerPointer<Pointer> buildArgs(java.util.List<IRValue> args) {
        LLVMValueRef[] arr = args.stream()
                .map(this::resolveValue)
                .toArray(LLVMValueRef[]::new);
        PointerPointer<Pointer> pp = new PointerPointer<>(arr.length);
        for (int i = 0; i < arr.length; i++) pp.put(i, arr[i]);
        return pp;
    }

    private void storeLocal(IRValue value, LLVMValueRef ref) {
        localValueMap.put(IRValue.nameOf(value), ref);
    }

    private String nameOf(IRValue value) {
        return IRValue.nameOf(value);
    }
}

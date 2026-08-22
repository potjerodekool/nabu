package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.backend.CompileOptions;
import io.github.potjerodekool.nabu.compiler.ir.IRGlobal;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
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
 *   BinaryOp (inclusief float-varianten), Alloca, AllocaArray, Load, Store,
 *   Call (intern + extern + indirect),
 *   Branch, CondBranch, Return, Cast, Phi, Move,
 *   InstanceOf (runtime helper), Throw (runtime helper), Pop
 *
 * Waarde-resolutie:
 *   - localValueMap  : tijdelijke registers + parameters (per functie)
 *   - globalValueMap : functies, string-globals, gedeclareerde globals, runtime helpers
 */
public class InstructionEmitter {

    private final LLVMBuilderRef              builder;
    private final LLVMContextRef              ctx;
    private final TypeMapper types;
    private final ConstantResolver constants;
    private final Map<String, LLVMValueRef>   globalValueMap;
    private final Map<String, LLVMBasicBlockRef> blockMap;
    private final CompileOptions opts;

    // Per-functie lokale registers
    private Map<String, LLVMValueRef> localValueMap;

    // Reference to FunctionEmitter for try-region tracking
    private FunctionEmitter functionEmitter;

    public InstructionEmitter(LLVMBuilderRef builder,
                              LLVMContextRef ctx,
                              TypeMapper types,
                              ConstantResolver constants,
                              Map<String, LLVMValueRef> globalValueMap,
                              Map<String, LLVMBasicBlockRef> blockMap,
                              CompileOptions opts) {
        this.builder        = builder;
        this.ctx            = ctx;
        this.types          = types;
        this.constants      = constants;
        this.globalValueMap = globalValueMap;
        this.blockMap       = blockMap;
        this.opts           = opts;
    }

    public void setLocalValueMap(Map<String, LLVMValueRef> localValueMap) {
        this.localValueMap = localValueMap;
    }

    public Map<String, LLVMValueRef> getLocalValueMap() {
        return localValueMap;
    }

    public void setFunctionEmitter(FunctionEmitter functionEmitter) {
        this.functionEmitter = functionEmitter;
    }

    // -------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------

    public void emit(IRInstruction instr) {
        switch (instr) {
            case IRInstruction.BinaryOp      op -> emitBinaryOp(op);
            case IRInstruction.Alloca         a -> emitAlloca(a);
            case IRInstruction.AllocaArray    aa -> emitAllocaArray(aa);
            case IRInstruction.Load        load -> emitLoad(load);
            case IRInstruction.Store      store -> emitStore(store);
            case IRInstruction.Call        call -> emitCall(call);
            case IRInstruction.IndirectCall ic -> emitIndirectCall(ic);
            case IRInstruction.Branch        br -> emitBranch(br);
            case IRInstruction.CondBranch    cb -> emitCondBranch(cb);
            case IRInstruction.Return       ret -> emitReturn(ret);
            case IRInstruction.Cast           c -> emitCast(c);
            case IRInstruction.Phi           p -> emitPhi(p);
            case IRInstruction.Move           m -> emitMove(m);
            case IRInstruction.InstanceOf    io -> emitInstanceOf(io);
            case IRInstruction.Throw       thr -> emitThrow(thr);
            case IRInstruction.Pop            p -> emitPop(p);
            case IRInstruction.HeapAlloc      ha -> emitHeapAlloc(ha);
            case IRInstruction.ArrayLoad      al -> emitArrayLoad(al);
            case IRInstruction.ArrayLength    al -> emitArrayLength(al);
            case IRInstruction.MonitorEnter   me -> emitMonitorEnter(me);
            case IRInstruction.MonitorExit    mx -> emitMonitorExit(mx);
            case IRInstruction.TryCatchRegion tc -> {
                // TryCatchRegion is metadata — LLVM handles via landingpad/invoke
                // For now, no-op
            }
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
            case ADD     -> LLVMBuildAdd (builder, left, right, name);
            case SUB     -> LLVMBuildSub (builder, left, right, name);
            case MUL     -> LLVMBuildMul (builder, left, right, name);
            case DIV     -> isFloat(op.left()) ? LLVMBuildFDiv(builder, left, right, name)
                                               : LLVMBuildSDiv(builder, left, right, name);
            case MOD     -> isFloat(op.left()) ? LLVMBuildFRem(builder, left, right, name)
                                               : LLVMBuildSRem(builder, left, right, name);
            case AND, BITAND -> LLVMBuildAnd (builder, left, right, name);
            case OR,  BITOR  -> LLVMBuildOr  (builder, left, right, name);
            case XOR, BITXOR -> LLVMBuildXor (builder, left, right, name);
            case EQ      -> isFloat(op.left()) ? LLVMBuildFCmp(builder, LLVMRealOEQ, left, right, name)
                                               : LLVMBuildICmp(builder, LLVMIntEQ,   left, right, name);
            case NEQ     -> isFloat(op.left()) ? LLVMBuildFCmp(builder, LLVMRealONE, left, right, name)
                                               : LLVMBuildICmp(builder, LLVMIntNE,   left, right, name);
            case LT      -> isFloat(op.left()) ? LLVMBuildFCmp(builder, LLVMRealOLT, left, right, name)
                                               : LLVMBuildICmp(builder, LLVMIntSLT,  left, right, name);
            case LTE     -> isFloat(op.left()) ? LLVMBuildFCmp(builder, LLVMRealOLE, left, right, name)
                                               : LLVMBuildICmp(builder, LLVMIntSLE,  left, right, name);
            case GT      -> isFloat(op.left()) ? LLVMBuildFCmp(builder, LLVMRealOGT, left, right, name)
                                               : LLVMBuildICmp(builder, LLVMIntSGT,  left, right, name);
            case GTE     -> isFloat(op.left()) ? LLVMBuildFCmp(builder, LLVMRealOGE, left, right, name)
                                               : LLVMBuildICmp(builder, LLVMIntSGE,  left, right, name);
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

    private void emitAllocaArray(IRInstruction.AllocaArray a) {
        switch (opts.gcStrategy()) {
            case NONE -> {
                LLVMTypeRef  elemType = types.map(a.allocType());
                LLVMValueRef size     = resolveValue(a.size());
                LLVMValueRef ptr      = LLVMBuildArrayAlloca(builder, elemType, size,
                        new BytePointer(nameOf(a.result())));
                storeLocal(a.result(), ptr);
            }
            case BOEHM -> {
                LLVMTypeRef elemType = types.map(a.allocType());
                LLVMValueRef count = resolveValue(a.size());
                LLVMValueRef elemSize = LLVMConstInt(LLVMInt64TypeInContext(ctx),
                        computeSizeOf(elemType), 0);
                LLVMValueRef totalSize = LLVMBuildMul(builder, count, elemSize,
                        new BytePointer(nameOf(a.result()) + ".size"));
                LLVMValueRef gcMalloc = globalValueMap.get("@GC_malloc");
                LLVMTypeRef gcMallocType = LLVMGlobalGetValueType(gcMalloc);
                PointerPointer<Pointer> args = new PointerPointer<>(1);
                args.put(0, totalSize);
                LLVMValueRef call = LLVMBuildCall2(builder, gcMallocType, gcMalloc,
                        args, 1, nameOf(a.result()));
                storeLocal(a.result(), call);
            }
            case REFCOUNT -> {
                LLVMTypeRef elemType = types.map(a.allocType());
                LLVMValueRef count = resolveValue(a.size());
                LLVMValueRef elemSize = LLVMConstInt(LLVMInt64TypeInContext(ctx),
                        computeSizeOf(elemType), 0);
                LLVMValueRef totalSize = LLVMBuildMul(builder, count, elemSize,
                        new BytePointer(nameOf(a.result()) + ".size"));
                LLVMValueRef mallocFn = globalValueMap.get("@malloc");
                LLVMTypeRef mallocType = LLVMGlobalGetValueType(mallocFn);
                PointerPointer<Pointer> args = new PointerPointer<>(1);
                args.put(0, totalSize);
                LLVMValueRef call = LLVMBuildCall2(builder, mallocType, mallocFn,
                        args, 1, nameOf(a.result()));
                storeLocal(a.result(), call);
            }
        }
    }

    private void emitHeapAlloc(IRInstruction.HeapAlloc ha) {
        switch (opts.gcStrategy()) {
            case NONE -> {
                LLVMValueRef ptr = LLVMBuildAlloca(builder,
                        types.map(ha.allocType()),
                        new BytePointer(nameOf(ha.result())));
                storeLocal(ha.result(), ptr);
            }
            case BOEHM -> {
                LLVMTypeRef allocType = types.map(ha.allocType());
                LLVMValueRef size = LLVMConstInt(LLVMInt64TypeInContext(ctx),
                        computeSizeOf(allocType), 0);
                LLVMValueRef gcMalloc = globalValueMap.get("@GC_malloc");
                LLVMTypeRef gcMallocType = LLVMGlobalGetValueType(gcMalloc);
                PointerPointer<Pointer> args = new PointerPointer<>(1);
                args.put(0, size);
                LLVMValueRef call = LLVMBuildCall2(builder, gcMallocType, gcMalloc,
                        args, 1, nameOf(ha.result()));
                storeLocal(ha.result(), call);
            }
            case REFCOUNT -> {
                LLVMTypeRef allocType = types.map(ha.allocType());
                LLVMValueRef size = LLVMConstInt(LLVMInt64TypeInContext(ctx),
                        computeSizeOf(allocType), 0);
                LLVMValueRef mallocFn = globalValueMap.get("@malloc");
                LLVMTypeRef mallocType = LLVMGlobalGetValueType(mallocFn);
                PointerPointer<Pointer> args = new PointerPointer<>(1);
                args.put(0, size);
                LLVMValueRef call = LLVMBuildCall2(builder, mallocType, mallocFn,
                        args, 1, nameOf(ha.result()));
                storeLocal(ha.result(), call);
            }
        }
    }

    private void emitArrayLoad(IRInstruction.ArrayLoad al) {
        LLVMValueRef array = resolveValue(al.array());
        LLVMValueRef index = resolveValue(al.index());

        LLVMTypeRef elemType = types.map(al.elemType());
        LLVMTypeRef ptrType = LLVMPointerType(elemType, 0);
        PointerPointer<Pointer> indices = new PointerPointer<>(1);
        indices.put(0, index);

        LLVMValueRef elemPtr = LLVMBuildGEP2(builder, elemType, array, indices, 1,
                nameOf(al.result()) + ".ptr");
        LLVMValueRef val = LLVMBuildLoad2(builder, elemType, elemPtr,
                nameOf(al.result()));
        storeLocal(al.result(), val);
    }

    private void emitArrayLength(IRInstruction.ArrayLength al) {
        // LLVM: array length is stored in the first element (header) of the array
        LLVMValueRef array = resolveValue(al.array());
        LLVMTypeRef i32Type = LLVMInt32TypeInContext(LLVMGetGlobalContext());
        LLVMTypeRef ptrType = LLVMPointerType(i32Type, 0);

        PointerPointer<Pointer> indices = new PointerPointer<>(1);
        indices.put(0, LLVMConstInt(i32Type, 0, 0));

        LLVMValueRef lenPtr = LLVMBuildGEP2(builder, i32Type, array, indices, 1,
                nameOf(al.result()) + ".len.ptr");
        LLVMValueRef len = LLVMBuildLoad2(builder, i32Type, lenPtr,
                nameOf(al.result()));
        storeLocal(al.result(), len);
    }

    private void emitMonitorEnter(IRInstruction.MonitorEnter me) {
        LLVMValueRef obj = resolveValue(me.object());
        LLVMValueRef fn = globalValueMap.get("@nabu_monitorenter");
        if (fn == null)
            throw new IllegalStateException("Runtime helper @nabu_monitorenter niet gedefinieerd");

        PointerPointer<Pointer> args = new PointerPointer<>(1);
        args.put(0, obj);

        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);
        LLVMBuildCall2(builder, fnType, fn, args, 1, "");
    }

    private void emitMonitorExit(IRInstruction.MonitorExit mx) {
        LLVMValueRef obj = resolveValue(mx.object());
        LLVMValueRef fn = globalValueMap.get("@nabu_monitorexit");
        if (fn == null)
            throw new IllegalStateException("Runtime helper @nabu_monitorexit niet gedefinieerd");

        PointerPointer<Pointer> args = new PointerPointer<>(1);
        args.put(0, obj);

        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);
        LLVMBuildCall2(builder, fnType, fn, args, 1, "");
    }

    private long computeSizeOf(LLVMTypeRef type) {
        LLVMTargetDataRef dataLayout = LLVMGetModuleDataLayout(
                LLVMGetGlobalParent(
                        LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))));
        return LLVMABISizeOfType(dataLayout, type);
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
        // Super-constructor-aanroep (super()/this.super()): java.lang.Object
        // heeft geen native state om te initialiseren in deze backend.
        // Zolang er geen inheritance/veld-inheritance is, is de super-call een no-op.
        if (call.function().endsWith("_super")) {
            return;
        }

        LLVMValueRef fn = globalValueMap.get("@" + call.function());
        if (fn == null)
            throw new IllegalStateException(
                "Onbekende functie: " + call.function());

        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);
        PointerPointer<Pointer> args = buildArgs(call.args());

        String name = call.result() != null ? nameOf(call.result()) : "";

        // Check of we in een try-regio zitten → invoke i.p.v. call
        String handlerLabel = getHandlerLabel();
        LLVMValueRef result;

        if (handlerLabel != null) {
            // Split het huidige blok na deze call:
            //   current_block: ... instructions ... invoke @fn() to label %cont unwind label %handler
            //   cont: <rest van instructies>
            LLVMBasicBlockRef currentBB = LLVMGetInsertBlock(builder);
            LLVMBasicBlockRef contBB = LLVMAppendBasicBlockInContext(ctx,
                    LLVMGetBasicBlockParent(currentBB),
                    new BytePointer("invoke.cont." + call.function()));
            LLVMBasicBlockRef handlerBB = blockMap.get(handlerLabel);

            if (handlerBB == null)
                throw new IllegalStateException("Handler blok niet gevonden: " + handlerLabel);

            result = LLVMBuildInvoke2(builder, fnType, fn, args,
                    call.args().size(), contBB, handlerBB, name);

            // Door gaan in het continuation blok
            LLVMPositionBuilderAtEnd(builder, contBB);
        } else {
            result = LLVMBuildCall2(builder, fnType, fn,
                    args, call.args().size(), name);
        }

        Reference.reachabilityFence(args);

        if (call.result() != null)
            storeLocal(call.result(), result);
    }

    private void emitIndirectCall(IRInstruction.IndirectCall ic) {
        LLVMValueRef callee    = resolveValue(ic.callee());
        LLVMTypeRef  fnType    = types.map(ic.fnType());
        PointerPointer<Pointer> args = buildArgs(ic.args());

        String name = ic.result() != null ? nameOf(ic.result()) : "";

        String handlerLabel = getHandlerLabel();
        LLVMValueRef result;

        if (handlerLabel != null) {
            LLVMBasicBlockRef currentBB = LLVMGetInsertBlock(builder);
            LLVMBasicBlockRef contBB = LLVMAppendBasicBlockInContext(ctx,
                    LLVMGetBasicBlockParent(currentBB),
                    new BytePointer("invoke.cont.icall"));
            LLVMBasicBlockRef handlerBB = blockMap.get(handlerLabel);

            if (handlerBB == null)
                throw new IllegalStateException("Handler blok niet gevonden: " + handlerLabel);

            result = LLVMBuildInvoke2(builder, fnType, callee, args,
                    ic.args().size(), contBB, handlerBB, name);

            LLVMPositionBuilderAtEnd(builder, contBB);
        } else {
            result = LLVMBuildCall2(builder, fnType, callee,
                    args, ic.args().size(), name);
        }

        Reference.reachabilityFence(args);

        if (ic.result() != null)
            storeLocal(ic.result(), result);
    }

    /**
     * Retourneert de handler-label als het huidige blok in een try-regio zit,
     * of null als het blok niet beschermd is.
     */
    private String getHandlerLabel() {
        if (functionEmitter == null) return null;
        LLVMBasicBlockRef currentBB = LLVMGetInsertBlock(builder);
        if (currentBB == null || currentBB.isNull()) return null;

        // Zoek het blok-label op basis van het LLVM blok
        for (var entry : blockMap.entrySet()) {
            if (entry.getValue().equals(currentBB)) {
                return functionEmitter.getHandlerLabel(entry.getKey());
            }
        }
        return null;
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
    // SSA Phi
    // -------------------------------------------------------

    private void emitPhi(IRInstruction.Phi phi) {
        final var llvmType = types.map(phi.result().type());
        final int count = phi.incomingValues().size();
        final var values = new PointerPointer<>(count);
        final var blocks = new PointerPointer<>(count);

        for (int i = 0; i < count; i++) {
            final var incoming = phi.incomingValues().get(i);
            values.put(i, resolveValue(incoming.value()));
            blocks.put(i, blockMap.get(incoming.fromBlock().label()));
        }

        final LLVMValueRef result = LLVMBuildPhi(builder, llvmType,
                new BytePointer(nameOf(phi.result())));
        LLVMAddIncoming(result, values, blocks, count);
        storeLocal(phi.result(), result);
    }

    // -------------------------------------------------------
    // SSA Move
    // -------------------------------------------------------

    private void emitMove(IRInstruction.Move move) {
        final var value = resolveValue(move.value());
        storeLocal(move.result(), value);
    }

    // -------------------------------------------------------
    // InstanceOf
    // -------------------------------------------------------

    private void emitInstanceOf(IRInstruction.InstanceOf instanceOf) {
        LLVMValueRef source   = resolveValue(instanceOf.source());
        LLVMTypeRef  boolType = types.map(IRType.BOOL);

        LLVMValueRef fn = globalValueMap.get("@nabu_instanceof");
        if (fn == null)
            throw new IllegalStateException("Runtime helper @nabu_instanceof niet gedefinieerd");

        String className = instanceOf.type().toString();
        LLVMValueRef classStr = constants.resolveString(className, IRGlobal.Linkage.PRIVATE);

        PointerPointer<Pointer> args = new PointerPointer<>(2);
        args.put(0, source);
        args.put(1, classStr);

        LLVMTypeRef  fnType = LLVMGlobalGetValueType(fn);
        LLVMValueRef result = LLVMBuildCall2(builder, fnType, fn, args, 2, "");

        result = LLVMBuildTrunc(builder, result, boolType,
                new BytePointer(nameOf(instanceOf.result())));
        storeLocal(instanceOf.result(), result);
    }

    // -------------------------------------------------------
    // Throw
    // -------------------------------------------------------

    private void emitThrow(IRInstruction.Throw throwInst) {
        // Maak een nieuw object aan en gooi het
        // Roep runtime helper aan: void nabu_throw(i8* exception)
        LLVMValueRef fn = globalValueMap.get("@nabu_throw");
        if (fn == null)
            throw new IllegalStateException("Runtime helper @nabu_throw niet gedefinieerd");

        // Resolv de waarde die gegooid wordt
        LLVMValueRef exValue = resolveValue(throwInst.result());

        PointerPointer<Pointer> args = new PointerPointer<>(1);
        args.put(0, exValue);

        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);
        LLVMBuildCall2(builder, fnType, fn, args, 1, "");
    }

    // -------------------------------------------------------
    // Pop
    // -------------------------------------------------------

    private void emitPop(IRInstruction.Pop pop) {
        // Pop: resolve het resultaat maar sla het nergens op
        // (verwijdert de bovenste waarde van de stack)
        if (pop.result() != null) {
            resolveValue(pop.result());
        }
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

            case IRValue.Values v -> {
                // Meerdere waarden: emitter elke waarde, retourneer de laatste
                LLVMValueRef last = null;
                for (IRValue val : v.values())
                    last = resolveValue(val);
                yield last;
            }

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

    private boolean isFloat(IRValue value) {
        return value.type() instanceof IRType.Float;
    }
}

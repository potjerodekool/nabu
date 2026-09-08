package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRGlobal;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;

import java.lang.ref.Reference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Deque;
import java.util.List;
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
    private final ClassLayouts classLayouts;

    // Per-functie lokale registers
    private Map<String, LLVMValueRef> localValueMap;

    // HeapAlloc-resultaten die wachten op hun constructor-aanroep.
    // Worden gekoppeld aan een volgende Call(SPECIAL, *_init) als 'this'.
    private final Deque<IRValue> pendingConstructorAllocs = new ArrayDeque<>();

    // Reference to FunctionEmitter for try-region tracking
    private FunctionEmitter functionEmitter;

    // Continuatie-blokken (gemaakt door een invoke) en hun try-handler-label,
    // zodat opeenvolgende calls in Ã©Ã©n IR-try-regio allemaal als invoke worden
    // geÃ«mit (de handler geldt ook voor het gegenereerde continuatie-blok).
    private final Map<LLVMBasicBlockRef, String> continuationHandlers = new HashMap<>();

    public InstructionEmitter(LLVMBuilderRef builder,
                              LLVMContextRef ctx,
                              TypeMapper types,
                              ConstantResolver constants,
                              Map<String, LLVMValueRef> globalValueMap,
                              Map<String, LLVMBasicBlockRef> blockMap,
                              CompileOptions opts,
                              ClassLayouts classLayouts) {
        this.builder        = builder;
        this.ctx            = ctx;
        this.types          = types;
        this.constants      = constants;
        this.globalValueMap = globalValueMap;
        this.blockMap       = blockMap;
        this.opts           = opts;
        this.classLayouts   = classLayouts;
    }

    public void setLocalValueMap(Map<String, LLVMValueRef> localValueMap) {
        this.localValueMap = localValueMap;
        this.continuationHandlers.clear();
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
            case IRInstruction.ArrayStore     as -> emitArrayStore(as);
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
        // String-concat: ADD op twee i8*-operandtypen -> nabu_concat runtime-helper
        if (op.op() == IRInstruction.BinaryOp.Op.ADD
                && isStringType(op.left().type())
                && isStringType(op.right().type())) {
            emitStringConcat(op);
            return;
        }

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

    private boolean isStringType(IRType t) {
        return t instanceof IRType.Ptr p && p.pointee().equals(IRType.I8);
    }

    private void emitStringConcat(IRInstruction.BinaryOp op) {
        LLVMValueRef left  = resolveValue(op.left());
        LLVMValueRef right = resolveValue(op.right());
        LLVMValueRef fn = globalValueMap.get("@nabu_concat");
        if (fn == null)
            throw new IllegalStateException("Runtime helper @nabu_concat niet gedefinieerd");

        PointerPointer<Pointer> args = new PointerPointer<>(2);
        args.put(0, left);
        args.put(1, right);

        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);
        LLVMValueRef result = LLVMBuildCall2(builder, fnType, fn, args, 2,
                nameOf(op.result()));
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
        // Layout: [i32 length][ element 0 ][ element 1 ]...
        // Totale bytes = ARRAY_HEADER_SIZE + count * elemSize
        LLVMTypeRef elemType = types.map(a.allocType());
        LLVMValueRef count = resolveValue(a.size());
        long elemSize = sizeOf(elemType);
        LLVMValueRef totalBytes = arrayTotalBytes(count, elemSize);
        LLVMValueRef array;
        switch (opts.gcStrategy()) {
            case NONE -> {
                array = LLVMBuildArrayAlloca(builder, LLVMInt8TypeInContext(ctx), totalBytes,
                        new BytePointer(nameOf(a.result())));
            }
            case BOEHM, REFCOUNT -> {
                LLVMValueRef allocFn = globalValueMap.get(opts.gcStrategy() == CompileOptions.GcStrategy.BOEHM
                        ? "@GC_malloc" : "@malloc");
                LLVMTypeRef allocType = LLVMGlobalGetValueType(allocFn);
                PointerPointer<Pointer> args = new PointerPointer<>(1);
                args.put(0, totalBytes);
                array = LLVMBuildCall2(builder, allocType, allocFn, args, 1,
                        nameOf(a.result()));
            }
            default -> throw new IllegalStateException(
                    "AllocaArray niet ondersteund voor GC-strategie: " + opts.gcStrategy());
        }
        storeArrayLength(array, count);
        storeLocal(a.result(), array);
    }

    private void emitHeapAlloc(IRInstruction.HeapAlloc ha) {
        // Registreer het resultaat voor koppeling aan de constructor-aanroep.
        pendingConstructorAllocs.addLast(ha.result());

        ClassLayouts.ClassLayout layout = classLayouts.getFor(ha.allocType());
        if (layout != null) {
            LLVMValueRef obj;
            switch (opts.gcStrategy()) {
                case NONE -> {
                    // stack-alloca van de object-struct (voor tests/kleine demo's)
                    obj = LLVMBuildAlloca(builder, layout.structType,
                            new BytePointer(nameOf(ha.result())));
                }
                case BOEHM, REFCOUNT -> {
                    long size = sizeOf(layout.structType);
                    LLVMValueRef sizeVal = LLVMConstInt(LLVMInt64TypeInContext(ctx), size, 0);
                    LLVMValueRef allocFn = globalValueMap.get(
                            opts.gcStrategy() == CompileOptions.GcStrategy.BOEHM
                                    ? "@GC_malloc" : "@malloc");
                    LLVMTypeRef allocType = LLVMGlobalGetValueType(allocFn);
                    PointerPointer<Pointer> args = new PointerPointer<>(1);
                    args.put(0, sizeVal);
                    obj = LLVMBuildCall2(builder, allocType, allocFn, args, 1,
                            nameOf(ha.result()));
                }
                default -> throw new IllegalStateException(
                        "HeapAlloc niet ondersteund voor GC-strategie: " + opts.gcStrategy());
            }
            initObjectHeader(obj, layout);
            storeLocal(ha.result(), obj);
            return;
        }

        // Fallback: geen bekende klasse — zoals voorheen een vaste buffer
        runHeapAllocFallback(ha);
    }

    private void runHeapAllocFallback(IRInstruction.HeapAlloc ha) {
        // Onbekende (library) klassen bv. java/lang/Exception: de backend kent
        // geen layout, dus we kunnen de object-header niet zelf in IR zetten.
        // De runtime alloceert dan een echt object met geldige type-info via
        // `i8* nabu_new_object(i8* internal_name, i64 size)` — zó dat
        // nabu_instanceof / nabu_can_catch op de header werken.
        String internalName = null;
        if (ha.allocType() instanceof IRType.Ptr p && p.jvmDescriptor() != null) {
            internalName = ClassLayouts.fromDescriptor(p.jvmDescriptor());
        }

        LLVMValueRef obj;
        if (internalName != null) {
            LLVMValueRef newObjFn = globalValueMap.get("@nabu_new_object");
            if (newObjFn == null) {
                throw new IllegalStateException("Runtime helper @nabu_new_object niet gedefinieerd");
            }
            LLVMValueRef nameStr = constants.resolveString(internalName, IRGlobal.Linkage.PRIVATE);
            LLVMValueRef sizeVal = LLVMConstInt(LLVMInt64TypeInContext(ctx), 64, 0);
            LLVMTypeRef newObjType = LLVMGlobalGetValueType(newObjFn);
            PointerPointer<Pointer> args = new PointerPointer<>(2);
            args.put(0, nameStr);
            args.put(1, sizeVal);
            obj = LLVMBuildCall2(builder, newObjType, newObjFn, args, 2,
                    nameOf(ha.result()));
        } else if (opts.gcStrategy() == CompileOptions.GcStrategy.NONE) {
            LLVMValueRef ptr = LLVMBuildAlloca(builder,
                    types.map(ha.allocType()),
                    new BytePointer(nameOf(ha.result())));
            obj = ptr;
        } else {
            LLVMTypeRef allocType = types.map(ha.allocType());
            LLVMValueRef size = LLVMConstInt(LLVMInt64TypeInContext(ctx),
                    computeSizeOf(allocType), 0);
            LLVMValueRef allocFn = globalValueMap.get(
                    opts.gcStrategy() == CompileOptions.GcStrategy.BOEHM
                            ? "@GC_malloc" : "@malloc");
            if (allocFn == null) {
                throw new IllegalStateException("Allocatie-helper niet gedefinieerd");
            }
            LLVMTypeRef allocFnType = LLVMGlobalGetValueType(allocFn);
            PointerPointer<Pointer> args = new PointerPointer<>(1);
            args.put(0, size);
            obj = LLVMBuildCall2(builder, allocFnType, allocFn, args, 1,
                    nameOf(ha.result()));
        }
        storeLocal(ha.result(), obj);
    }

    private void initObjectHeader(LLVMValueRef obj, ClassLayouts.ClassLayout layout) {
        // obj[0] = header-struct H; daarna H[0]=type-info, H[1]=vtable
        LLVMValueRef headerPtr = LLVMBuildStructGEP2(builder, layout.structType, obj, 0,
                ".header");
        LLVMValueRef typeSlot = LLVMBuildStructGEP2(builder,
                classLayouts.objectHeaderType(), headerPtr, ClassLayouts.HEADER_SLOT_TYPE,
                ".type.slot");
        LLVMBuildStore(builder, layout.typeInfoGlobal, typeSlot);

        LLVMValueRef vtableSlot = LLVMBuildStructGEP2(builder,
                classLayouts.objectHeaderType(), headerPtr, ClassLayouts.HEADER_SLOT_VTABLE,
                ".vtable.slot");
        LLVMBuildStore(builder, layout.vtableGlobal, vtableSlot);

        LLVMValueRef itableSlot = LLVMBuildStructGEP2(builder,
                classLayouts.objectHeaderType(), headerPtr, ClassLayouts.HEADER_SLOT_ITABLE,
                ".itable.slot");
        LLVMBuildStore(builder, layout.itableGlobal, itableSlot);
    }

    private void storeArrayLength(LLVMValueRef array, LLVMValueRef count) {
        // array[0] (i32) = count
        PointerPointer<Pointer> idx = new PointerPointer<>(1);
        idx.put(0, LLVMConstInt(LLVMInt64TypeInContext(ctx), 0, 0));
        LLVMValueRef lenSlot = LLVMBuildGEP2(builder, LLVMInt32TypeInContext(ctx),
                array, idx, 1, ".len.ptr");
        LLVMValueRef countI32 = LLVMBuildIntCast2(builder, count, LLVMInt32TypeInContext(ctx),
                0, new BytePointer(".len"));
        LLVMBuildStore(builder, countI32, lenSlot);
        Reference.reachabilityFence(idx);
    }

    private LLVMValueRef arrayTotalBytes(LLVMValueRef count, long elemSize) {
        LLVMTypeRef i64 = LLVMInt64TypeInContext(ctx);
        LLVMValueRef c = LLVMBuildIntCast2(builder, count, i64, 0, new BytePointer(".c"));
        LLVMValueRef elemSz = LLVMConstInt(i64, elemSize, 0);
        LLVMValueRef dataBytes = LLVMBuildMul(builder, c, elemSz, new BytePointer(".data"));
        LLVMValueRef headerSize = LLVMConstInt(i64, ARRAY_HEADER_SIZE, 0);
        return LLVMBuildAdd(builder, headerSize, dataBytes, new BytePointer(".total"));
    }

    private void emitArrayLoad(IRInstruction.ArrayLoad al) {
        LLVMValueRef array = resolveValue(al.array());
        LLVMTypeRef elemType = types.map(al.elemType());
        LLVMValueRef elemPtr = arrayElementPtr(array, resolveValue(al.index()), elemType);
        LLVMValueRef val = LLVMBuildLoad2(builder, elemType, elemPtr,
                new BytePointer(nameOf(al.result())));
        storeLocal(al.result(), val);
    }

    private void emitArrayStore(IRInstruction.ArrayStore as) {
        LLVMValueRef array = resolveValue(as.array());
        LLVMTypeRef elemType = types.map(as.elemType());
        LLVMValueRef elemPtr = arrayElementPtr(array, resolveValue(as.index()), elemType);
        LLVMBuildStore(builder, resolveValue(as.value()), elemPtr);
    }

    private void emitArrayLength(IRInstruction.ArrayLength al) {
        LLVMValueRef array = resolveValue(al.array());
        PointerPointer<Pointer> idx = new PointerPointer<>(1);
        idx.put(0, LLVMConstInt(LLVMInt64TypeInContext(ctx), 0, 0));
        LLVMValueRef lenPtr = LLVMBuildGEP2(builder, LLVMInt32TypeInContext(ctx),
                array, idx, 1, nameOf(al.result()) + ".len.ptr");
        LLVMValueRef len = LLVMBuildLoad2(builder, LLVMInt32TypeInContext(ctx), lenPtr,
                new BytePointer(nameOf(al.result())));
        storeLocal(al.result(), len);
        Reference.reachabilityFence(idx);
    }

    /** Berekent de pointer naar element @p index: array + ARRAY_HEADER_SIZE + index*elemSize. */
    private LLVMValueRef arrayElementPtr(LLVMValueRef array, LLVMValueRef index,
                                         LLVMTypeRef elemType) {
        // base = array + ARRAY_HEADER_SIZE
        PointerPointer<Pointer> hIdx = new PointerPointer<>(1);
        hIdx.put(0, LLVMConstInt(LLVMInt64TypeInContext(ctx), ARRAY_HEADER_SIZE, 0));
        LLVMValueRef base = LLVMBuildGEP2(builder, LLVMInt8TypeInContext(ctx), array,
                hIdx, 1, ".arr.base");
        // elemPtr = base + index*elemSize
        PointerPointer<Pointer> eIdx = new PointerPointer<>(1);
        eIdx.put(0, index);
        LLVMValueRef elemPtr = LLVMBuildGEP2(builder, elemType, base, eIdx, 1,
                ".arr.elem");
        Reference.reachabilityFence(hIdx);
        Reference.reachabilityFence(eIdx);
        return elemPtr;
    }

    private static final long ARRAY_HEADER_SIZE = 8;

    private long sizeOf(LLVMTypeRef type) {
        LLVMTargetDataRef dl = LLVMCreateTargetData(new BytePointer(""));
        long size = LLVMABISizeOfType(dl, type);
        LLVMDisposeTargetData(dl);
        return size;
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
        IRValue target = load.ptr();

        // Object-veldtoegang: Values(obj, Named(...))
        if (isObjectFieldAccess(target)) {
            LLVMTypeRef pointee = types.map(objectFieldPointeeType(target));
            LLVMValueRef llvmPtr = resolveObjectFieldPointer(target);
            LLVMValueRef val = LLVMBuildLoad2(builder, pointee, llvmPtr,
                    new BytePointer(nameOf(load.result())));
            storeLocal(load.result(), val);
            return;
        }

        IRType ptrType = target.type();

        // Defensieve check: ptr moet een pointer-type zijn
        if (!(ptrType instanceof IRType.Ptr ptr)) {
            throw new IllegalStateException(
                    "emitLoad verwacht een pointer-type, maar kreeg: "
                            + ptrType + " voor waarde: " + nameOf(target)
                            + "\nTip: parameters zijn geen pointers — gebruik ze direct "
                            + "of sla ze op via emitAlloca + emitStore.");
        }

        LLVMValueRef llvmPtr = resolveValue(target);
        LLVMValueRef val     = LLVMBuildLoad2(builder,
                types.map(ptr.pointee()), llvmPtr,
                new BytePointer(nameOf(load.result())));
        storeLocal(load.result(), val);
    }

    private void emitStore(IRInstruction.Store store) {
        IRValue target = store.ptr();
        LLVMValueRef ptr = isObjectFieldAccess(target)
                ? resolveObjectFieldPointer(target)
                : resolveValue(target);
        LLVMBuildStore(builder, resolveValue(store.value()), ptr);
    }

    // -------------------------------------------------------
    // Aanroepen
    // -------------------------------------------------------

    private void emitCall(IRInstruction.Call call) {
        // Super-constructor-aanroep (super(...)): de <Class>_super-call is een
        // frontend-bridge. Als de super-klasse in deze compilatie zit, emitteer
        // een DIRECTE call naar diens <Class>_init (args bevatten al 'this' als
        // eerste parameter); anders blijft het een no-op (de super heeft dan
        // geen native state die hier geïnitialiseerd moet worden).
        if (call.function().endsWith("_super")) {
            tryEmitSuperConstructor(call);
            return;
        }

        // Virtuele dispatch: als de receiver een bekende klasse is met een
        // vtable-slot voor deze methode, roepen we indirect via de vtable aan.
        if (call.callKind() == CallKind.VIRTUAL && tryEmitVirtualCall(call)) {
            return;
        }

        // Interface-dispatch: via de itable van de receiver-klasse.
        if (call.callKind() == CallKind.INTERFACE && tryEmitInterfaceCall(call)) {
            return;
        }

        LLVMValueRef fn = globalValueMap.get("@" + call.function());
        if (fn == null && isConstructorCall(call))
            fn = declareExternalConstructor(call.function());
        if (fn == null)
            throw new IllegalStateException(
                "Onbekende functie: " + call.function());

        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);

        // Constructor-aanroep: koppel het voorafgaande HeapAlloc-resultaat als
        // 'this' (eerste parameter), net zoals de Java-backend dit doet.
        List<IRValue> callArgs = call.args();
        if (isConstructorCall(call)) {
            callArgs = prependThis(call.function(), call.args());
        }

        PointerPointer<Pointer> args = buildArgs(callArgs);
        int argCount = callArgs.size();

        LLVMValueRef result = emitCallOrInvoke(call.function(), fnType, fn, args,
                argCount, call.result());
        Reference.reachabilityFence(args);

        if (call.result() != null)
            storeLocal(call.result(), result);
    }

    /**
     * Emitteert de super-constructor-keten: een {@code <Class>_super}-call wordt
     * vertaald naar een directe call naar {@code <Class>_init} wanneer die functie
     * in deze compilatie aanwezig is (de super-klasse is dan mee ge-compileerd).
     * De argumenten van de {@code _super}-call bevatten 'this' al als eerste
     * parameter (frontend-conventie), dus er wordt geen 'this' toegevoegd.
     *
     * @return {@code true} als een echte call is ge-emitteerd; {@code false} als
     *         de super-klasse niet in deze compilatie zit (no-op, zoals voorheen).
     */
    private boolean tryEmitSuperConstructor(IRInstruction.Call call) {
        String fn = call.function();
        String initName = fn.substring(0, fn.length() - "_super".length()) + "_init";
        LLVMValueRef fnVal = globalValueMap.get("@" + initName);
        if (fnVal == null || fnVal.isNull()) return false;

        LLVMTypeRef fnType = LLVMGlobalGetValueType(fnVal);
        PointerPointer<Pointer> args = buildArgs(call.args());
        emitCallOrInvoke(initName, fnType, fnVal, args, call.args().size(), call.result());
        Reference.reachabilityFence(args);
        return true;
    }

    /**
     * Declareert een als extern bekendte constructor (bv. een klasse uit de
     * runtime/rt zoals {@code java.lang.Exception}) waarvan de '&lt;init&gt;'
     * in deze module niet zelf gecodeward wordt. In het objectmodel van deze
     * backend wordt 'this' als een opaque i8* doorgegeven en is de return void.
     */
    private LLVMValueRef declareExternalConstructor(String name) {
        LLVMBasicBlockRef bb = LLVMGetInsertBlock(builder);
        LLVMValueRef currentFnRef = LLVMGetBasicBlockParent(bb);
        LLVMModuleRef mod = LLVMGetGlobalParent(currentFnRef);

        LLVMTypeRef objPtr = LLVMPointerTypeInContext(ctx, 0);
        PointerPointer<Pointer> params = new PointerPointer<>(1);
        params.put(0, objPtr);
        LLVMTypeRef fnType = LLVMFunctionType(LLVMVoidTypeInContext(ctx), params, 1, 0);

        LLVMValueRef ext = LLVMAddFunction(mod, new BytePointer(name), fnType);
        LLVMSetLinkage(ext, LLVMExternalLinkage);
        globalValueMap.put("@" + name, ext);
        return ext;
    }

    /**
     * Probeert een virtuele aanroep via de vtable van de receiver te emitteren.
     * {@code true} als de virtuele dispatch daadwerkelijk is geëmitteerd;
     * anders {@code false} (geen bekende klasse of geen vtable-slot).
     */
    private boolean tryEmitVirtualCall(IRInstruction.Call call) {
        if (call.args().isEmpty()) return false;

        IRValue receiver = call.args().get(0);
        LLVMValueRef obj = resolveValue(receiver);

        ClassLayouts.ClassLayout layout = classLayouts.getFor(receiver.type());
        // Wanneer het statische type van de receiver geen owner-layout oplevert
        // (bv. na SSA-optimalisatie: receiver is een opaque pointer geworden),
        // val terug op de layout van de declarerende klasse van de methode.
        if (layout == null) {
            layout = classLayouts.findLayoutForVtableSlot(call.function());
        }
        if (layout == null) return false;

        int slot = layout.vtableSlot(call.function());
        if (slot < 0) return false;

        LLVMTypeRef ptrType = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);

        // obj.vtable — header-struct veld 1 (via outer struct index 0)
        LLVMValueRef headerPtr = LLVMBuildStructGEP2(builder, layout.structType, obj, 0,
                ".header");
        LLVMValueRef vtablePtrSlot = LLVMBuildStructGEP2(builder,
                classLayouts.objectHeaderType(), headerPtr, ClassLayouts.HEADER_SLOT_VTABLE,
                ".vtable.slot");
        LLVMValueRef vtable = LLVMBuildLoad2(builder, ptrType, vtablePtrSlot, ".vtable");

        // vtable[slot]
        PointerPointer<Pointer> idx = new PointerPointer<>(1);
        idx.put(0, LLVMConstInt(LLVMInt64TypeInContext(ctx), slot, 0));
        LLVMValueRef fnPtrSlot = LLVMBuildGEP2(builder, ptrType, vtable, idx, 1,
                ".vtable.fn");
        Reference.reachabilityFence(idx);
        LLVMValueRef fnPtr = LLVMBuildLoad2(builder, ptrType, fnPtrSlot, ".fnptr");

        // Signatuur: (this, paramTypes...) -> returnType
        List<IRType> fnParams = new ArrayList<>(call.paramTypes().size() + 1);
        fnParams.add(new IRType.Ptr(IRType.I8));
        fnParams.addAll(call.paramTypes());
        LLVMTypeRef fnType = types.map(new IRType.Function(call.returnType(), fnParams));

        PointerPointer<Pointer> args = buildArgs(call.args());
        LLVMValueRef result = emitCallOrInvoke(call.function(), fnType, fnPtr, args,
                call.args().size(), call.result());
        Reference.reachabilityFence(args);

        if (call.result() != null)
            storeLocal(call.result(), result);
        return true;
    }

    /**
     * Probeert een interface-aanroep via de itable van de receiver te emitteren.
     * {@code true} als de dispatch is ge-emitteerd; anders {@code false}.
     */
    private boolean tryEmitInterfaceCall(IRInstruction.Call call) {
        if (call.args().isEmpty()) return false;

        IRValue receiver = call.args().get(0);
        LLVMValueRef obj = resolveValue(receiver);

        ClassLayouts.ClassLayout layout = classLayouts.getFor(receiver.type());
        if (layout == null) return false;

        int slot = layout.itableSlot(call.function());
        if (slot < 0) return false;

        LLVMTypeRef ptrType = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);

        // obj.itable -- header-struct veld 2 (via outer struct index 0)
        LLVMValueRef headerPtr = LLVMBuildStructGEP2(builder, layout.structType, obj, 0,
                ".header");
        LLVMValueRef itablePtrSlot = LLVMBuildStructGEP2(builder,
                classLayouts.objectHeaderType(), headerPtr, ClassLayouts.HEADER_SLOT_ITABLE,
                ".itable.slot");
        LLVMValueRef itable = LLVMBuildLoad2(builder, ptrType, itablePtrSlot, ".itable");

        // itable[slot]
        PointerPointer<Pointer> idx = new PointerPointer<>(1);
        idx.put(0, LLVMConstInt(LLVMInt64TypeInContext(ctx), slot, 0));
        LLVMValueRef fnPtrSlot = LLVMBuildGEP2(builder, ptrType, itable, idx, 1,
                ".itable.fn");
        Reference.reachabilityFence(idx);
        LLVMValueRef fnPtr = LLVMBuildLoad2(builder, ptrType, fnPtrSlot, ".ifnptr");

        // Signatuur: (this, paramTypes...) -> returnType
        List<IRType> fnParams = new ArrayList<>(call.paramTypes().size() + 1);
        fnParams.add(new IRType.Ptr(IRType.I8));
        fnParams.addAll(call.paramTypes());
        LLVMTypeRef fnType = types.map(new IRType.Function(call.returnType(), fnParams));

        PointerPointer<Pointer> args = buildArgs(call.args());
        LLVMValueRef result = emitCallOrInvoke(call.function(), fnType, fnPtr, args,
                call.args().size(), call.result());
        Reference.reachabilityFence(args);

        if (call.result() != null)
            storeLocal(call.result(), result);
        return true;
    }

    /**
     * Emitteert een {@code call} of {@code invoke} (als het huidige blok in een
     * try-regio zit) en retourneert het resultaat.
     */
    private LLVMValueRef emitCallOrInvoke(String calleeName,
                                          LLVMTypeRef fnType,
                                          LLVMValueRef fn,
                                          PointerPointer<Pointer> args,
                                          int argCount,
                                          IRValue resultValue) {
        String name = resultValue != null ? nameOf(resultValue) : "";

        String handlerLabel = getHandlerLabel();
        if (handlerLabel != null) {
            LLVMBasicBlockRef currentBB = LLVMGetInsertBlock(builder);
            LLVMBasicBlockRef contBB = LLVMAppendBasicBlockInContext(ctx,
                    LLVMGetBasicBlockParent(currentBB),
                    new BytePointer("invoke.cont." + calleeName));
            LLVMBasicBlockRef handlerBB = blockMap.get(handlerLabel);

            if (handlerBB == null)
                throw new IllegalStateException("Handler blok niet gevonden: " + handlerLabel);

            continuationHandlers.put(contBB, handlerLabel);
            LLVMValueRef result = LLVMBuildInvoke2(builder, fnType, fn, args,
                    argCount, contBB, handlerBB, name);
            LLVMPositionBuilderAtEnd(builder, contBB);
            return result;
        }
        return LLVMBuildCall2(builder, fnType, fn, args, argCount, name);
    }

    /**
     * Herkent een constructor-aanroep: {@code _init} met {@code this} als
     * impliciete eerste parameter.
     */
    private boolean isConstructorCall(IRInstruction.Call call) {
        return call.callKind() == CallKind.SPECIAL
                && call.function().endsWith("_init");
    }

    /**
     * Prepends the pending HeapAlloc-object ('this') aan de constructor-argumenten.
     */
    private List<IRValue> prependThis(String functionName, List<IRValue> args) {
        IRValue thisVal = pendingConstructorAllocs.pollLast();
        if (thisVal == null) {
            throw new IllegalStateException(
                    "Constructor-aanroep '" + functionName
                            + "' zonder voorafgaande HeapAlloc (this ontbreekt)");
        }
        List<IRValue> result = new ArrayList<>(args.size() + 1);
        result.add(thisVal);
        result.addAll(args);
        return result;
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

            continuationHandlers.put(contBB, handlerLabel);

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

        // Continuatie-blok (uit een eerdere invoke in dezelfde try-regio):
        // erf dezelfde handler.
        String contHandler = continuationHandlers.get(currentBB);
        if (contHandler != null) return contHandler;

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
        LLVMValueRef fn = globalValueMap.get("@nabu_throw");
        if (fn == null)
            throw new IllegalStateException("Runtime helper @nabu_throw niet gedefinieerd");

        // De te gooien waarde; de frontend vult result() na het alloceren van
        // het exception-object. Als deze nog null is, alloceren we een
        // placeholder-object van het gegooid type (fallback).
        LLVMValueRef exValue = throwInst.result() != null
                ? resolveValue(throwInst.result())
                : ensureThrowableValue(throwInst.type());

        PointerPointer<Pointer> args = new PointerPointer<>(1);
        args.put(0, exValue);
        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);

        String handlerLabel = getHandlerLabel();
        if (handlerLabel != null) {
            // Throw binnen een try-regio: gooi als invoke naar de handler,
            // zodat de Itanium unwinder het ocept richting deze functie.
            LLVMBasicBlockRef currentBB = LLVMGetInsertBlock(builder);
            LLVMBasicBlockRef contBB = LLVMAppendBasicBlockInContext(ctx,
                    LLVMGetBasicBlockParent(currentBB),
                    new BytePointer("throw.cont"));
            LLVMBasicBlockRef handlerBB = blockMap.get(handlerLabel);
            if (handlerBB == null)
                throw new IllegalStateException("Handler blok niet gevonden: " + handlerLabel);
            continuationHandlers.put(contBB, handlerLabel);
            LLVMBuildInvoke2(builder, fnType, fn, args, 1, contBB, handlerBB, "");
            LLVMPositionBuilderAtEnd(builder, contBB);
            // nabu_throw keert nooit normaal terug.
            LLVMBuildUnreachable(builder);
        } else {
            // Onbevangen throw: directe call, daarna unreachable.
            LLVMBuildCall2(builder, fnType, fn, args, 1, "");
            LLVMBuildUnreachable(builder);
        }
    }

    /**
     * Alloceert een placeholder-exception-object voor het gegeven type.
     * Wordt gebruikt wanneer de frontend (nog) geen exception-object vult.
     */
    private LLVMValueRef ensureThrowableValue(IRType thrownType) {
        ClassLayouts.ClassLayout layout = classLayouts.getFor(thrownType);
        if (layout == null) {
            return LLVMBuildAlloca(builder, LLVMInt8TypeInContext(ctx),
                    new BytePointer(".exc"));
        }
        LLVMValueRef obj;
        switch (opts.gcStrategy()) {
            case NONE -> obj = LLVMBuildAlloca(builder, layout.structType,
                    new BytePointer(".exc"));
            case BOEHM, REFCOUNT -> {
                long size = sizeOf(layout.structType);
                LLVMValueRef sizeVal = LLVMConstInt(LLVMInt64TypeInContext(ctx), size, 0);
                LLVMValueRef allocFn = globalValueMap.get(
                        opts.gcStrategy() == CompileOptions.GcStrategy.BOEHM
                                ? "@GC_malloc" : "@malloc");
                if (allocFn == null)
                    throw new IllegalStateException("Allocatie-helper ontbreekt");
                LLVMTypeRef allocType = LLVMGlobalGetValueType(allocFn);
                PointerPointer<Pointer> args = new PointerPointer<>(1);
                args.put(0, sizeVal);
                obj = LLVMBuildCall2(builder, allocType, allocFn, args, 1, ".exc");
            }
            default -> throw new IllegalStateException(
                    "Throw-placeholder niet ondersteund voor GC-strategie: " + opts.gcStrategy());
        }
        initObjectHeader(obj, layout);
        return obj;
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

    // -------------------------------------------------------
    // Object-veldtoegang
    // -------------------------------------------------------

    /**
     * Herkent Values(obj, Named(...)) voor een INSTANTIE-veld.
     * Statische velden worden als globale behandeld (niet hier).
     */
    private boolean isObjectFieldAccess(IRValue value) {
        if (!(value instanceof IRValue.Values values)) return false;
        var list = values.values();
        if (list.isEmpty()) return false;
        IRValue last = list.getLast();
        if (!(last instanceof IRValue.Named named)) return false;
        return !named.isStatic() && named.ownerType() != null;
    }

    /** Retourneert het IRType van het veld dat door een field-access waarde wordt aangeduid. */
    private IRType objectFieldPointeeType(IRValue value) {
        IRValue.Named named = (IRValue.Named) ((IRValue.Values) value).values().getLast();
        return named.type();
    }

    /** Resolveert Values(obj, Named(...)) naar een GEP-pointer in het object-struct. */
    private LLVMValueRef resolveObjectFieldPointer(IRValue value) {
        IRValue.Values values = (IRValue.Values) value;
        var list = values.values();
        IRValue.Named named = (IRValue.Named) list.getLast();

        // receiver-object = alles vóór de Named
        LLVMValueRef obj = resolveValue(list.get(0));

        ClassLayouts.ClassLayout layout = classLayouts.getFor(named.ownerType());
        if (layout == null) {
            throw new IllegalStateException(
                    "Geen object-layout geregistreerd voor veld-eigenaar: " + named.ownerType());
        }
        int structIndex = layout.structIndex(named.fieldIndex());
        if (structIndex < 0) {
            throw new IllegalStateException(
                    "Geen struct-offset voor veld '" + named.name()
                            + "' (fieldIndex=" + named.fieldIndex() + ") in " + layout.internalName);
        }

        LLVMValueRef fieldPtr = LLVMBuildStructGEP2(builder, layout.structType, obj, structIndex,
                ".field." + named.name());
        return fieldPtr;
    }
}


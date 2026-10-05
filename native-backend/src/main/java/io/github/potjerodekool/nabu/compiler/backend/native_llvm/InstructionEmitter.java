package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
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

    // GcStrategy.REFCOUNT: retain/release-emissie. Inactief (en dus gratis)
    // voor de andere strategieën.
    private final RefCountEmitter refCounts;

    // Per-functie lokale registers
    private Map<String, LLVMValueRef> localValueMap;

// HeapAlloc-resultaten die wachten op hun constructor-aanroep.
// Worden gekoppeld aan een volgende Call(SPECIAL, *_init) als 'this'.
    private final Deque<IRValue> pendingConstructorAllocs = new ArrayDeque<>();
    // De meest recente HeapAlloc in de huidige functie. Nodig als de
    // wachtrij leeg is bij een constructor-aanroep: een delegerende
    // constructor ('this(...)') deelt het object van de eerste aanroep, dus
    // is er maar één HeapAlloc voor twee *_init-calls.
    private IRValue lastAllocatedObject;
    // Naam van de functie die momenteel geëmitteerd wordt (diagnostiek).
    private String currentFunctionName;
    // Naam van de 'this'-parameter van de huidige constructor.
    private String thisParameterName;

    // Velden waarvan al gemeld is dat ze niet benaderbaar zijn (eenmalig
    // waarschuwen per veld).
    private final java.util.Set<String> warnedUnsupportedFields = new java.util.LinkedHashSet<>();

    /** Reset de per-functie staat aan het begin van elke functie-body. */
    public void beginFunction(IRFunction fn) {
        this.currentFunctionName = fn != null ? fn.name : null;
        this.pendingConstructorAllocs.clear();
        this.lastAllocatedObject = null;
        this.thisParameterName = fn != null && fn.isConstructor() && !fn.params.isEmpty()
                ? nameOf(fn.params.get(0))
                : null;
    }

    /**
     * Herkent de impliciete {@code this} van de functie die geëmitteerd wordt.
     * De frontend geeft die bij een super-constructor-aanroep ZELF mee als
     * eerste argument (bv. {@code [%this, %0]}); in dat geval mag
     * {@link #prependThis} niets toevoegen.
     */
    private boolean isFunctionThis(IRValue value) {
        if (!(value instanceof IRValue.Temp temp) || temp.name() == null) {
            return false;
        }
        return temp.name().equals(thisParameterName) || temp.name().contains("this");
    }

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
        this.refCounts      = new RefCountEmitter(builder, ctx, types, globalValueMap,
                opts.gcStrategy() == CompileOptions.GcStrategy.REFCOUNT,
                this::resolveValue);
    }

    /** De REFCOUNT-emitter, zodat {@link FunctionEmitter} de exit-pad kan afronden. */
    RefCountEmitter refCounts() {
        return refCounts;
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
            case IRInstruction.Unreachable     u -> emitUnreachable();
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
        // String-concat: ADD met minstens één String-operand (bv. "" + int of
        // count + "..*") -> nabu_concat; het resultaat is dan een String.
        if (op.op() == IRInstruction.BinaryOp.Op.ADD
                && (isStringType(op.left().type()) || isStringType(op.right().type()))) {
            emitStringConcat(op);
            return;
        }

        LLVMValueRef left  = resolveValue(op.left());
        LLVMValueRef right = resolveValue(op.right());
        BytePointer  name  = new BytePointer(nameOf(op.result()));

        LLVMValueRef result = switch (op.op()) {
            case ADD     -> isFloat(op.left()) ? LLVMBuildFAdd(builder, left, right, name)
                                               : LLVMBuildAdd (builder, left, right, name);
            case SUB     -> isFloat(op.left()) ? LLVMBuildFSub(builder, left, right, name)
                                               : LLVMBuildSub (builder, left, right, name);
            case MUL     -> isFloat(op.left()) ? LLVMBuildFMul(builder, left, right, name)
                                               : LLVMBuildMul (builder, left, right, name);
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
        LLVMValueRef left  = toConcatOperand(op.left());
        LLVMValueRef right = toConcatOperand(op.right());
        LLVMValueRef fn = globalValueMap.get("@nabu_concat");
        if (fn == null)
            throw new IllegalStateException("Runtime helper @nabu_concat niet gedefinieerd");

        PointerPointer<Pointer> args = new PointerPointer<>(2);
        args.put(0, left);
        args.put(1, right);
        Reference.reachabilityFence(args);

        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);
        LLVMValueRef result = LLVMBuildCall2(builder, fnType, fn, args, 2,
                nameOf(op.result()));
        storeLocal(op.result(), result);
    }

    /**
     * Een concat-operand: een string blijft zoals hij is, een primitieve waarde
     * wordt eerst naar zijn stringvorm omgezet (Java-semantiek van {@code "" + x}).
     */
    private LLVMValueRef toConcatOperand(IRValue operand) {
        IRType type = operand.type();
        if (isStringType(type)) {
            return resolveValue(operand);
        }
        LLVMValueRef fn;
        if (type instanceof IRType.Int intType && intType.bits() == 64) {
            fn = globalValueMap.get("@nabu_long_to_string");
        } else if (type instanceof IRType.Int) {
            fn = globalValueMap.get("@nabu_int_to_string");
        } else if (type instanceof IRType.Float floatType && floatType.bits() == 32) {
            fn = globalValueMap.get("@nabu_float_to_string");
        } else if (type instanceof IRType.Float) {
            fn = globalValueMap.get("@nabu_double_to_string");
        } else if (type instanceof IRType.Bool) {
            LLVMValueRef boolFn = globalValueMap.get("@nabu_bool_to_string");
            if (boolFn == null) {
                throw new IllegalStateException("Runtime helper @nabu_bool_to_string niet gedefinieerd");
            }
            PointerPointer<Pointer> boolArgs = new PointerPointer<>(1);
            boolArgs.put(0, LLVMBuildZExt(builder, resolveValue(operand),
                    LLVMInt32TypeInContext(ctx), new BytePointer(".toint")));
            Reference.reachabilityFence(boolArgs);
            return LLVMBuildCall2(builder, LLVMGlobalGetValueType(boolFn), boolFn, boolArgs, 1,
                    ".tostr");
        } else if (type instanceof IRType.Ptr) {
            // Object-referentie in string-concat: degradeer tot lege string om
            // de grote picocli-compilatie voorlopig door de linker/compiler te
            // krijgen. Toekomstige verbetering: runtime nabu_object_to_string().
            return constants.resolveString("", IRGlobal.Linkage.PRIVATE);
        } else {
            throw new IllegalStateException(
                    "String-concat met een niet-string, niet-primitief operand: " + type
                            + " (waarde: " + nameOf(operand) + ")");
        }
        if (fn == null) {
            throw new IllegalStateException(
                    "Runtime helper voor " + type + " -> string niet gedefinieerd");
        }
        LLVMValueRef value = resolveValue(operand);
        if (type instanceof IRType.Int intType && intType.bits() < 32) {
            // nabu_int_to_string neemt i32, maar char/byte/short zijn hier
            // nog smal (bv. het resultaat van String.charAt). JLS 5.3
            // promoteert char naar int zonder teken, dus zero-extend.
            value = LLVMBuildZExt(builder, value,
                    LLVMInt32TypeInContext(ctx), new BytePointer(".toint"));
        }
        PointerPointer<Pointer> args = new PointerPointer<>(1);
        args.put(0, value);
        Reference.reachabilityFence(args);
        return LLVMBuildCall2(builder, LLVMGlobalGetValueType(fn), fn, args, 1, ".tostr");
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
        // Switch-expressie: de GcStrategy-cases zijn compiler-gedekt. Een
        // nieuwe strategie levert dan een compilefout op in plaats van een
        // misleidende "niet ondersteund"-runtime-exceptie.
        LLVMValueRef array = switch (opts.gcStrategy()) {
            case NONE -> LLVMBuildArrayAlloca(builder, LLVMInt8TypeInContext(ctx), totalBytes,
                    new BytePointer(nameOf(a.result())));
            case BOEHM, REFCOUNT -> {
                LLVMValueRef allocFn = globalValueMap.get(opts.gcStrategy() == CompileOptions.GcStrategy.BOEHM
                        ? "@GC_malloc" : "@malloc");
                LLVMTypeRef allocType = LLVMGlobalGetValueType(allocFn);
                PointerPointer<Pointer> args = new PointerPointer<>(1);
                args.put(0, totalBytes);
                yield LLVMBuildCall2(builder, allocType, allocFn, args, 1,
                        nameOf(a.result()));
            }
        };
        storeArrayLength(array, count);
        storeLocal(a.result(), array);
        refCounts.onAlloc(a.result(), array);
    }

    private void emitHeapAlloc(IRInstruction.HeapAlloc ha) {
        // Registreer het resultaat voor koppeling aan de constructor-aanroep.
        pendingConstructorAllocs.addLast(ha.result());
        lastAllocatedObject = ha.result();

        ClassLayouts.ClassLayout layout = classLayouts.getFor(ha.allocType());
        if (layout != null) {
            // Zie emitAllocaArray: switch-expressie, zodat de exhaustiviteit
            // van de GcStrategy-cases door de compiler wordt bewaakt.
            LLVMValueRef obj = switch (opts.gcStrategy()) {
                case NONE ->
                    // stack-alloca van de object-struct (voor tests/kleine demo's)
                    LLVMBuildAlloca(builder, layout.structType,
                            new BytePointer(nameOf(ha.result())));
                case BOEHM, REFCOUNT -> {
                    long size = sizeOf(layout.structType);
                    LLVMValueRef sizeVal = LLVMConstInt(LLVMInt64TypeInContext(ctx), size, 0);
                    LLVMValueRef allocFn = globalValueMap.get(
                            opts.gcStrategy() == CompileOptions.GcStrategy.BOEHM
                                    ? "@GC_malloc" : "@malloc");
                    LLVMTypeRef allocType = LLVMGlobalGetValueType(allocFn);
                    PointerPointer<Pointer> args = new PointerPointer<>(1);
                    args.put(0, sizeVal);
                    yield LLVMBuildCall2(builder, allocType, allocFn, args, 1,
                            nameOf(ha.result()));
                }
            };
            initObjectHeader(obj, layout);
            storeLocal(ha.result(), obj);
            refCounts.onAlloc(ha.result(), obj);
            return;
        }

        // Fallback: geen bekende klasse — zoals voorheen een vaste buffer
        runHeapAllocFallback(ha);
    }

    private void runHeapAllocFallback(IRInstruction.HeapAlloc ha) {
        // Onbekende (library) klassen bv. java/lang/Exception: de backend kent
        // geen layout, dus de runtime kan de object-header niet voor ons neerzetten
        // — die moet in IR gebeuren. Wij reserveren daarom zelf geheugen, met de
        // allocator van de gekozen strategie, en geven het aan de runtime die de
        // type-info in de header zet (`i8* nabu_init_object(i8* obj, i8* name)`).
        //
        // De grootte is exact één header: van instancevelden weten we niets, dus
        // extra ruimte zou alleen een stille overloop elders zijn.
        final long headerSize = computeSizeOf(classLayouts.objectHeaderType());

        LLVMValueRef obj;
        if (ha.allocType() instanceof IRType.Ptr p && p.jvmDescriptor() != null) {
            final String internalName = ClassLayouts.fromDescriptor(p.jvmDescriptor());
            obj = allocForStrategy(headerSize, nameOf(ha.result()));
            // GC-strategie-onafhankelijk: het runtime-type-info komt van de naam.
            final LLVMValueRef initObjFn = globalValueMap.get("@nabu_init_object");
            if (initObjFn == null) {
                throw new IllegalStateException("Runtime helper @nabu_init_object niet gedefinieerd");
            }
            final LLVMValueRef nameStr = constants.resolveString(internalName, IRGlobal.Linkage.PRIVATE);
            PointerPointer<Pointer> args = new PointerPointer<>(2);
            args.put(0, obj);
            args.put(1, nameStr);
            obj = LLVMBuildCall2(builder, LLVMGlobalGetValueType(initObjFn), initObjFn, args, 2,
                    nameOf(ha.result()) + ".init");
        } else {
            // Geen interne naam (bv. een Ptr zonder descriptor): niemand kan dit
            // object identificeren, dus de header blijft leeg. Allocatie volgt
            // dezelfde strategie, zodat een GC-programma hier geen malloc-lek krijgt.
            obj = allocForStrategy(headerSize, nameOf(ha.result()));
        }
        storeLocal(ha.result(), obj);
        refCounts.onAlloc(ha.result(), obj);
    }

    /**
     * Reserveert {@code size} bytes met de allocator van de gekozen GC-strategie:
     * stack (NONE), {@code GC_malloc} (BOEHM) of {@code malloc} (REFCOUNT).
     *
     * De switch is bewust een switch-expressie: de GcStrategy-cases zijn dan
     * compiler-gedekt en een nieuwe strategie levert een compilefout op in plaats
     * van een stilletjes verkeerde allocator.
     */
    private LLVMValueRef allocForStrategy(final long size, final String name) {
        return switch (opts.gcStrategy()) {
            case NONE -> {
                // Een array-alloca (i8[size]) en géén alloca van het gemapte
                // type: dat is voor een onbekende klasse `i8` — één byte, terwijl
                // de header 40 bytes nodig heeft.
                yield LLVMBuildArrayAlloca(builder, LLVMInt8TypeInContext(ctx),
                        LLVMConstInt(LLVMInt64TypeInContext(ctx), size, 0),
                        new BytePointer(name));
            }
            case BOEHM, REFCOUNT -> {
                final LLVMValueRef allocFn = globalValueMap.get(
                        opts.gcStrategy() == CompileOptions.GcStrategy.BOEHM
                                ? "@GC_malloc" : "@malloc");
                if (allocFn == null) {
                    throw new IllegalStateException("Allocatie-helper niet gedefinieerd");
                }
                final LLVMValueRef sizeVal = LLVMConstInt(LLVMInt64TypeInContext(ctx), size, 0);
                PointerPointer<Pointer> args = new PointerPointer<>(1);
                args.put(0, sizeVal);
                yield LLVMBuildCall2(builder, LLVMGlobalGetValueType(allocFn), allocFn, args, 1,
                        name);
            }
        };
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

        // De teller moet vanaf een bekende waarde beginnen: malloc, GC_malloc en
        // de stack geven ongeïnitialiseerd geheugen terug, en nabu_retain telt op
        // wat er staat. Zonder deze nulstore zou een object nooit op 0 komen en
        // dus nooit vrijgegeven worden. Ook de andere strategieën krijgen de
        // nulstore: de header is dan nooit half ongeïnitialiseerd, ook niet als
        // iemand later naar de teller kijkt (bv. een debug-tool).
        LLVMValueRef countSlot = LLVMBuildStructGEP2(builder,
                classLayouts.objectHeaderType(), headerPtr,
                ClassLayouts.HEADER_SLOT_REF_COUNT, ".refcount.slot");
        LLVMBuildStore(builder, LLVMConstInt(LLVMInt32TypeInContext(ctx), 0, 0), countSlot);
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
            LLVMValueRef llvmPtr = resolveObjectFieldPointer(target);
            if (llvmPtr == null) {
                // Geen layout (bibliotheekklasse): lezen degradeert naar undef.
                LLVMValueRef undef = LLVMGetUndef(
                        types.map(objectFieldPointeeType(target)));
                storeLocal(load.result(), undef);
                return;
            }
            LLVMTypeRef pointee = types.map(objectFieldPointeeType(target));
            LLVMValueRef val = LLVMBuildLoad2(builder, pointee, llvmPtr,
                    new BytePointer(nameOf(load.result())));
            storeLocal(load.result(), val);
            return;
        }

        IRType ptrType = target.type();

        // Statisch veld: het frontend-type is hier het WAARDE-type, terwijl
        // resolveValue het adres van de static global teruggeeft. Laad dan de
        // waarde van die global.
        //
        // De conditie mag NIET op de vorm van het type steunen: een
        // referentie-type is zelf een Ptr(I8, descriptor). Zou die tak dan
        // worden overgeslagen en belandt de load in de generieke
        // pointer-route, die `pointee()` (dus i8) laadt in plaats van de
        // verwijzing zelf. Voor primitieven (geen Ptr) gold die route ook
        // al; de static-ness dekt beide gevallen.
        if (target instanceof IRValue.Named namedStatic && namedStatic.isStatic()) {
            LLVMValueRef global = resolveValue(target);
            LLVMTypeRef valueType = LLVMGlobalGetValueType(global);
            if (valueType == null || valueType.isNull()) {
                valueType = types.map(ptrType);
            }
            LLVMValueRef val = LLVMBuildLoad2(builder, valueType, global,
                    new BytePointer(nameOf(load.result())));
            storeLocal(load.result(), val);
            return;
        }

        LLVMValueRef llvmPtr;
        LLVMTypeRef loadType;
        if (target instanceof IRValue.Temp t && t.name().startsWith("%phi")) {
            // SSA-phi-slot: een `Temp` met een t-ye (i32 e.d.) verwijst naar
            // een gedeeld stackslot (aangemaakt in FunctionEmitter.emitBody).
            LLVMValueRef slotPtr = localValueMap.get(t.name());
            if (slotPtr == null) {
                throw new IllegalStateException(
                        "Geen phi-slot alloca voor: " + t.name());
            }
            // Alloca[type=phi-temp-type] -> load met het resultaat-type.
            loadType = types.map(load.result() != null
                    ? load.result().type() : t.type());
            LLVMValueRef val = LLVMBuildLoad2(builder, loadType, slotPtr,
                    new BytePointer(nameOf(load.result())));
            storeLocal(load.result(), val);
            return;
        }

        // Defensieve check: ptr moet een pointer-type zijn
        if (!(ptrType instanceof IRType.Ptr ptr)) {
            throw new IllegalStateException(
                    "emitLoad verwacht een pointer-type, maar kreeg: "
                            + ptrType + " voor waarde: " + nameOf(target)
                            + "\nTip: parameters zijn geen pointers — gebruik ze direct "
                            + "of sla ze op via emitAlloca + emitStore.");
        }

        llvmPtr = resolveValue(target);
        loadType = types.map(ptr.pointee());
        LLVMValueRef val     = LLVMBuildLoad2(builder,
                loadType, llvmPtr,
                new BytePointer(nameOf(load.result())));
        storeLocal(load.result(), val);
    }

    private void emitStore(IRInstruction.Store store) {
        IRValue target = store.ptr();
        LLVMValueRef ptr = isObjectFieldAccess(target)
                ? resolveObjectFieldPointer(target)
                : resolveValue(target);
        if (ptr == null) {
            // Geen layout (bibliotheekklasse): schrijven zou buiten het
            // object vallen, dus deze toewijzing wordt overgeslagen.
            return;
        }
        // GcStrategy.REFCOUNT: de toewijzing die vrijkomt. De waarde die in
        // de slot zegt release'n we vóór de overschrijving; de nieuwe waarde
        // neemt de eigendom over van de frame (zie RefCountEmitter).
        refCounts.onStoreToSlot(target, ptr);
        LLVMBuildStore(builder, resolveValue(store.value()), ptr);
    }

    // -------------------------------------------------------
    // Aanroepen
    // -------------------------------------------------------

    /**
     * De callee-parameters incl. de impliciete receiver: non-static callkinds
     * hebben de JVM-receiver als eerste 'arg' — de callee's IRFunction draagt
     * %this als eerste param, dus de overload-sleutel moet matchen.
     */
    private List<IRType> callParamsWithReceiver(final IRInstruction.Call call) {
        // Delegateer aan FnKeys: de overload-sleutel waarmee een callee
        // GEDECLAREERD wordt moet identiek zijn aan de sleutel waarmee hier
        // naar het symbol gezocht wordt. Twee losse implementaties liepen
        // hier uiteen (descriptorloos receiver vs. receiver mét descriptor)
        // en misten dan het eigen symbool ('PI32I32' vs 'P…I32I32').
        return new ArrayList<>(FnKeys.callParams(call));
    }

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

        // Interface-dispatch: via de itable van de receiver-klasse. Ook wanneer
        // het statische type een interface is maar de call als VIRTUAL is
        // gemarkeerd: het object is dan een concrete implementatie en de enige
        // juiste route is de itable.
        if ((call.callKind() == CallKind.INTERFACE || isInterfaceReceiver(call))
                && tryEmitInterfaceCall(call)) {
            return;
        }

        LLVMValueRef fn = null;
        if (isConstructorCall(call)) {
            // Een constructor die met een methode van dezelfde naam botst, staat
            // onder een eigen symbool: de gewone naam is die van de methode.
            // De parameterlijst is nodig om de juiste overload te kiezen:
            // overloads delen de IR-functienaam.
            fn = lookupDeclared(call.function(), callParamsWithReceiver(call),
                    call.returnType());
            if (fn == null || fn.isNull()) {
                // LET OP: de overload-sleutel wordt mét '@' opgeslagen
                // (FunctionEmitter.declareSignature), dus de lookup hoort hier
                // ook de '@' te bevatten. Zonder die prefix mist elke
                // sleutel-lookup en valt de call terug op de kale naam, waar de
                // laatste overload wint — LLVM meldt dan "Incorrect number of
                // arguments passed to called function".
                fn = globalValueMap.get("@" + FnKeys.fnKey(
                        call.function(), callParamsWithReceiver(call)));
            }
            if (fn == null || fn.isNull()) {
                // Alleen als de kale naam ook qua aantal argumenten past. Een
                // ad-hoc extern ('_init' van een niet-gecompileerde klasse) is
                // onder de kale naam geregistreerd met de shape van de EERSTE
                // call; zonder deze check bond een call met een ander aantal
                // argumenten daar aan en volgde "Incorrect number of arguments".
                fn = globalValueMap.get("@" + call.function());
                if (!arityMatches(fn, emittedArgCount(call))) {
                    fn = null;
                }
            }
        } else {
            fn = globalValueMap.get("@" + FnKeys.fnKey(
                    call.function(), call.paramTypes()));
            if (fn == null || fn.isNull()) {
                // Legacy: ook de receiver-meetellende sleutel proberen.
                fn = globalValueMap.get("@" + FnKeys.fnKey(
                        call.function(), callParamsWithReceiver(call)));
            }
            if (fn == null || fn.isNull()) {
                // Legacy-lookups (bestaande gedeclareerden zonder overload-sleutel)
                fn = globalValueMap.get("@" + call.function());
                if (!arityMatches(fn, emittedArgCount(call))) {
                    fn = null;
                }
            }
            if (fn == null || fn.isNull()) {
                // De functie is wél in deze compilatie gedeclareerd, maar dan onder
                // de simple-owner naam terwijl de call de gekwalificeerde naam
                // gebruikt (bv. 'package.Owner_method' -> 'Owner_method').
                fn = lookupDeclared(call.function(), callParamsWithReceiver(call),
                        emittedArgCount(call), call.returnType());
            }
        }
        if (fn == null || fn.isNull()) {
            if (isConstructorCall(call)) {
                fn = declareExternalConstructor(call);
            }
        }
        if (fn == null || fn.isNull()) {
            final var diagParams = callParamsWithReceiver(call);
            final var resolved = classLayouts.resolveFunction(call.function(), diagParams);
            final var argTypes = call.args().stream()
                    .map(a -> String.valueOf(a.type())).toList();
            final var knownKeys = globalValueMap.keySet().stream()
                    .filter(k -> k.contains(simpleNameOf(call.function())))
                    .sorted().toList();
            throw new IllegalStateException(
                    "Onbekende functie: " + FnKeys.fnKey(call.function(), diagParams)
                            + "\n  in IR-functie   : " + enclosingFunctionName()
                            + "\n  call.function(): " + call.function()
                            + "\n  callKind       : " + call.callKind()
                            + "\n  paramTypes     : " + call.paramTypes()
                            + "\n  args           : " + call.args().size() + " " + argTypes
                            + "\n  resolveFunction: " + (resolved == null
                                    ? "null" : resolved.name + " params=" + resolved.params.stream()
                                            .map(IRValue::type).toList())
                            + "\n  bekende sleutels: " + knownKeys);
        }

        // Het fnType van de daadwerkelijke gedeclareerde callee is de enige
        // bron van waarheid die gegarandeerd consistent is met de args die we
        // emitten (incl. de impliciete receiver). De overload-key-lookup
        // garandeert dat 'fn' de juiste overload is (bh. Math.max(ptr,ptr));
        // een elders gedeclareerde variant met een ander type-profiel wordt
        // daardoor niet per abuis gebruikt.
        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);

        // Constructor-aanroep: koppel het voorafgaande HeapAlloc-resultaat als
        // 'this' (eerste parameter), net zoals de Java-backend dit doet.
        List<IRValue> callArgs = call.args();
        if (isConstructorCall(call)) {
            callArgs = prependThis(call.function(), call.args());
        }

        PointerPointer<Pointer> args = buildArgs(callArgs);
        int argCount = callArgs.size();

        if (fnType == null || fnType.isNull()) {
            throw new IllegalStateException(
                    "fnctype null voor " + call.function()
                            + " paramTypes=" + call.paramTypes());
        }
        if (fn == null || fn.isNull()) {
            throw new IllegalStateException("fn null voor " + call.function());
        }

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
        LLVMValueRef fnVal = globalValueMap.get("@" + FnKeys.fnKey(
                initName, callParamsWithReceiver(call)));
        if (fnVal == null || fnVal.isNull()) {
            fnVal = lookupDeclared(initName, callParamsWithReceiver(call));
        }
        if (fnVal == null || fnVal.isNull()) {
            fnVal = globalValueMap.get("@" + initName);
        }
        if (fnVal == null || fnVal.isNull()) {
            // De super-klasse is mee gecompileerd, maar de frontend schreef de
            // constructor onder de simple-owner naam (bv. 'Animal_init' i.p.v.
            // 'pkg.Animal_init').
            fnVal = lookupDeclared(initName, callParamsWithReceiver(call));
        }
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
    private LLVMValueRef declareExternalConstructor(IRInstruction.Call call) {
        String name = call.function();
        String key = "@" + FnKeys.fnKey(name, callParamsWithReceiver(call));

        // Elke parameterlijst krijgt zijn eigen declaratie: dezelfde
        // '_init' deelt de IR-functienaam tussen constructors met en zonder
        // argumenten. Eén gedeclareerde shape per naam liet de andere
        // overloads een verkeerd aantal argumenten krijgen.
        LLVMValueRef existing = globalValueMap.get(key);
        if (existing != null && !existing.isNull()
                && arityMatches(existing, emittedArgCount(call))) {
            return existing;
        }

        LLVMBasicBlockRef bb = LLVMGetInsertBlock(builder);
        LLVMValueRef currentFnRef = LLVMGetBasicBlockParent(bb);
        LLVMModuleRef mod = LLVMGetGlobalParent(currentFnRef);

        // De signatuur volgt uit de call-site, inclusief de impliciete
        // receiver. Die receiver staat NIET in call.args() (bv. een constructor
        // 'new ArrayList(5)' heeft daar alleen het argument), maar wordt bij het
        // emitteren wél als eerste operand meegegeven. Een declaratie uit
        // call.args() alleen was daardoor één parameter korter dan de call.
        final int nArgs = emittedArgCount(call);
        List<IRType> callParams = callParamsWithReceiver(call);
        // Bescherm tegen een IR-vorm waarin de profiel-lijst één langer is dan
        // het aantal te emitteren operanden: de LLVM-declaratie moet altijd
        // precies de geëmitteerde operanden beschrijven.
        if (callParams.size() != nArgs) {
            callParams = new ArrayList<>(callParams.subList(
                    0, Math.min(callParams.size(), nArgs)));
        }
        LLVMTypeRef[] paramArray = new LLVMTypeRef[callParams.size()];
        for (int i = 0; i < paramArray.length; i++) {
            paramArray[i] = types.map(callParams.get(i));
        }
        PointerPointer<Pointer> params = new PointerPointer<>(paramArray.length);
        for (int i = 0; i < paramArray.length; i++) {
            params.put(i, paramArray[i]);
        }
        LLVMTypeRef fnType = LLVMFunctionType(LLVMVoidTypeInContext(ctx), params,
                paramArray.length, 0);

        LLVMValueRef ext = LLVMAddFunction(mod, new BytePointer(name), fnType);
        LLVMSetLinkage(ext, LLVMExternalLinkage);
        globalValueMap.put(key, ext);
        globalValueMap.putIfAbsent("@" + name, ext);
        return ext;
    }

    /** Of {@code fn} precies {@code arity} parameters heeft; null-safe. */
    private boolean arityMatches(final LLVMValueRef fn, final int arity) {
        if (fn == null || fn.isNull()) return false;
        // Let op: niet via de functietype tellen. LLVMGetElementType op een
        // FunctionType geeft het RETOURtype, niet de parameterlijst, waardoor
        // elke functie 0 parameters leek.
        return LLVMCountParams(fn) == arity;
    }

    /**
     * Probeert een virtuele aanroep via de vtable van de receiver te emitteren.
     * {@code true} als de virtuele dispatch daadwerkelijk is geëmitteerd;
     * anders {@code false} (geen bekende klasse of geen vtable-slot).
     */
    /** Of het statische type van de receiver een interface is. */
    private boolean isInterfaceReceiver(final IRInstruction.Call call) {
        if (call.args().isEmpty()) return false;
        final ClassLayouts.ClassLayout layout =
                classLayouts.getFor(call.args().get(0).type());
        return layout != null && layout.isInterface;
    }

    private boolean tryEmitVirtualCall(IRInstruction.Call call) {
        if (call.args().isEmpty()) return false;

        IRValue receiver = call.args().get(0);
        LLVMValueRef obj = resolveValue(receiver);

        // De IR-laag bewaart het receiver-type soms alleen als een opaque pointer
        // zonder JVM-descriptor (bv. na SSA/optimalisatie of via een temporaar).
        // Dan is er geen layout bij het statische type, maar de vtable-dispatch
        // hoort dan bij de klasse die de methode daadwerkelijk declareert.
        ClassLayouts.ClassLayout layout = classLayouts.getFor(receiver.type());
        if (layout == null || layout.vtableGlobal == null || layout.isInterface) {
            layout = classLayouts.findLayoutForVtableSlot(
                    call.function(), callParamsWithReceiver(call));
        }
        // Een vtable-dispatch is alleen geldig als díe klasse ook een vtable
        // heeft. Ontbreekt de global, dan wijst de vtable-pointer in de header
        // naar een andere (of lege) array en is elke slot-index betekenisloos.
        if (layout == null || layout.vtableGlobal == null || layout.isInterface) {
            // GEEN fallback naar een andere klasse: de slot-index geldt
            // relatief aan de vtable van de ontvanger. Een index uit de vtable
            // van een andere klasse (bv. CommandLine met 123 slots) wijst in de
            // vtable van de ontvanger naar een willekeurige functie met een
            // ander parameterprofiel — LLVM weigert dat met "Incorrect number
            // of arguments". Beter een directe call dan een verkeerde vtable.
            //
            // Voor een interface-receiver geldt hetzelfde: zijn vtable is leeg,
            // dus dispatch hoort via de itable (tryEmitInterfaceCall).
            return false;
        }

        // Met de parameterlijst: overloads delen de IR-functienaam en hebben elk
        // een eigen vtable-slot. Zonder parameterlijst kreeg elke variant het
        // slot van de laatste en riep een call de verkeerde functie aan.
        List<IRType> slotParams = callParamsWithReceiver(call);
        int slot = layout.vtableSlot(call.function(), slotParams);
        if (slot < 0) {
            // De IR declareert met de simple eigenaar, de call gebruikt de
            // gekwalificeerde naam: het slot staat ook onder die naam
            // geregistreerd, maar zoek het alsnog op de functie zelf.
            IRFunction declared = classLayouts.resolveFunction(
                    call.function(), slotParams, call.returnType());
            slot = declared != null
                    ? layout.vtableSlot(declared.name, slotParams) : -1;
        }
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
     * Zoekt een functie die onder een ANDERE naam gedeclareerd is dan de
     * call-target: de IR declareert met de simple eigenaar ({@code Owner_method})
     * terwijl de call de gekwalificeerde naam gebruikt ({@code pkg.Owner_method}).
     * Zonder dit zou elke aanroep van een klasse in een package falen.
 */
    private static String simpleNameOf(final String qualified) {
        final int idx = qualified.lastIndexOf('_');
        return idx < 0 ? qualified : qualified.substring(idx + 1);
    }

    private String enclosingFunctionName() {
        return functionEmitter == null
                ? "?" : functionEmitter.currentFnNameForDiagnostics();
    }

    private LLVMValueRef lookupDeclared(String callTarget, List<IRType> params) {
        return lookupDeclared(callTarget, params, -1, null);
    }

    private LLVMValueRef lookupDeclared(String callTarget, List<IRType> params,
                                        IRType expectedReturnType) {
        return lookupDeclared(callTarget, params, -1, expectedReturnType);
    }

    private LLVMValueRef lookupDeclared(String callTarget, List<IRType> params,
                                        int emittedArgs,
                                        IRType expectedReturnType) {
        final IRFunction declared = classLayouts.resolveFunction(
                callTarget, params, expectedReturnType);
        if (declared == null) return null;
        // De sleutel waarmee dit IRFunction GEDEKLAREERD is, sleutelt op de
        // expliciete parameters (zonder receiver) — zie FnKeys.declaredParamsOf.
        final List<IRType> declaredParams = FnKeys.declaredParamsOf(declared);
        if (declared == null) return null;

        // Een constructor die met een methode van dezelfde naam botst staat
        // onder een eigen symbool.
        final String symbol = firstNonNull(
                classLayouts.constructorSymbol(declared), declared.name);
        LLVMValueRef value = globalValueMap.get("@" + FnKeys.fnKey(symbol, declaredParams));
        if (value == null || value.isNull()) {
            value = globalValueMap.get("@" + FnKeys.fnKey(symbol, params));
        }
        if (value == null || value.isNull()) {
            value = globalValueMap.get("@" + symbol);
        }
        if ((value == null || value.isNull()) && !symbol.equals(declared.name)) {
            value = globalValueMap.get("@" + declared.name);
            if (value == null || value.isNull()) {
                value = globalValueMap.get("@" + FnKeys.fnKey(declared.name, params));
            }
        }
        // Ook hier moet het aantal parameters kloppen. De kale naam en de
        // naam-alias dekken samen maar één overload; zonder deze check bond een
        // constructor-call aan een variant met een ander aantal argumenten.
        final int expected = emittedArgs >= 0 ? emittedArgs : params.size();
        if (!arityMatches(value, expected)) {
            return null;
        }
        return value;
    }

    private static String firstNonNull(String first, String fallback) {
        return first != null ? first : fallback;
    }

    /**
     * Probeert een interface-aanroep via de itable van de receiver te emitteren.
     * {@code true} als de dispatch is ge-emitteerd; anders {@code false}.
     */
    private boolean tryEmitInterfaceCall(IRInstruction.Call call) {
        if (call.args().isEmpty()) return false;

        IRValue receiver = call.args().get(0);
        LLVMValueRef obj = resolveValue(receiver);

        int slot = -1;
        ClassLayouts.ClassLayout layout = classLayouts.getFor(receiver.type());
        if (layout != null) {
            slot = layout.itableSlot(call.function());
        }
        if (slot < 0) {
            // Het statische type is een interface zonder eigen layout-slot (bv.
            // een receiver die naar een opaque pointer is gedegenereerd): val terug
            // op de globale slot-index van de interface-methode.
            slot = classLayouts.interfaceSlotIndex(call.function());
        }
        if (slot < 0) return false;

        LLVMTypeRef ptrType = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);

        // Het itable-veld staat op dezelfde index in de header van elk object,
        // dus de concrete layout van het ontvangertype is hier niet nodig: een
        // GEP op de header zelf volstaat (en werkt ook als het ontvangertype
        // tot een pointer zonder descriptor is gedegenereerd).
        LLVMValueRef itablePtrSlot = LLVMBuildStructGEP2(builder,
                classLayouts.objectHeaderType(), obj,
                ClassLayouts.HEADER_SLOT_ITABLE, ".itable.slot");
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
     * Het aantal operanden waarmee de call daadwerkelijk geëmitteerd wordt.
     *
     * <p>Niet per definitie {@code callParamsWithReceiver(...).size()}: alleen
     * bij een constructor wordt 'this' vooraan toegevoegd. Bij handgeschreven IR
     * (bv. een VIRTUAL call zonder receiver-operand) telt het
     * parameterprofiel er één te veel, terwijl er één operand wordt geëmitteerd —
     * een arity-check op het profiel zou dan de enige juiste declaratie
     * verwerpen.
     */
    private int emittedArgCount(final IRInstruction.Call call) {
        return isConstructorCall(call) ? call.args().size() + 1 : call.args().size();
    }

    /**
     * Prepends the pending HeapAlloc-object ('this') aan de constructor-argumenten.
     */
    private List<IRValue> prependThis(String functionName, List<IRValue> args) {
        // Een openstaande HeapAlloc betekent dat deze call bij een `new`-expressie
        // hoort: het gealloceerde object IS de receiver. Dat moet vóór de
        // `this`-heuristiek worden nagegaan, want bij een niet-statische member
        // class is args[0] de ENCLOSING instance, die toevallig ook de `this`
        // van de huidige functie kan zijn.
        IRValue thisVal = pendingConstructorAllocs.pollLast();
        if (thisVal == null) {
            if (!args.isEmpty() && isFunctionThis(args.get(0))) {
                // Delegerende constructor (`this(...)`): het object is al
                // gealloceerd en de eerste aanroep heeft 'this' al verbruikt.
                return args;
            }
            thisVal = lastAllocatedObject;
        }
        if (thisVal == null) {
            throw new IllegalStateException(
                    "Constructor-aanroep '" + functionName
                            + "' zonder voorafgaande HeapAlloc (this ontbreekt)"
                            + "; functie=" + currentFunctionName
                            + ", args=" + args);
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
        if (target == null) {
            // Optimizer-reductie-artefact: het blok-label is na
            // removeRedundantBranches weggevallen. Repoets naar het dichtst
            // bijzijnde bestaande blok met dezelfde label-prefix; anders
            // `unreachable` (de ASM-backend converteert dezelfde branches
            // immers weg).
            for (var entry : blockMap.entrySet()) {
                if (br.targetLabel().startsWith(entry.getKey())
                        || entry.getKey().startsWith(
                                br.targetLabel().replaceAll("\\.\\d+$", ""))) {
                    target = entry.getValue();
                    break;
                }
            }
            if (target == null) {
                LLVMBuildUnreachable(builder);
                return;
            }
        }
        LLVMBuildBr(builder, target);
    }

    private void emitCondBranch(IRInstruction.CondBranch cb) {
        LLVMBasicBlockRef trueBlock  = blockMap.get(cb.trueLabel());
        LLVMBasicBlockRef falseBlock = blockMap.get(cb.falseLabel());
        if (trueBlock == null && falseBlock != null) {
            trueBlock = falseBlock;
        } else if (falseBlock == null && trueBlock != null) {
            falseBlock = trueBlock;
        }
        if (trueBlock == null || falseBlock == null) {
            throw new IllegalStateException("Onbekend branch-labels: " + cb.trueLabel() + " / " + cb.falseLabel());
        }
        LLVMBuildCondBr(builder, resolveValue(cb.condition()), trueBlock, falseBlock);
    }

    private void emitReturn(IRInstruction.Return ret) {
        if (refCounts.isEnabled()) {
            // REFCOUNT: alle returns sprongen naar de ene exit-pad, die de
            // frame-eigen referenties afgeeft en daarna teruggeeft.
            refCounts.onReturn(ret.value());
            return;
        }
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

        // De runtime vergelijkt interne namen ("java/lang/Exception"), niet
        // de java-descriptor ("Ljava/lang/Exception;") en al zeker niet
        // type.toString() (dat is "ptr"). Een Ptr met jvmDescriptor zetten we
        // daarom om; alleen typen zonder descriptor vallen terug op de naam.
        String className = instanceOf.type() instanceof IRType.Ptr p && p.jvmDescriptor() != null
                ? ClassLayouts.fromDescriptor(p.jvmDescriptor())
                : instanceOf.type().toString();
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

    /**
 * Sluit een blok af waarvan het pad onbereikbaar is. LLVM eist een
 * terminator per basic block; zonder deze instructie weigert de verifier
 * de module.
 */
private void emitUnreachable() {
        LLVMBuildUnreachable(builder);
    }

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
        // Zie emitAllocaArray: switch-expressie, exhaustiviteit wordt bewaakt.
        LLVMValueRef obj = switch (opts.gcStrategy()) {
            case NONE -> LLVMBuildAlloca(builder, layout.structType,
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
                yield LLVMBuildCall2(builder, allocType, allocFn, args, 1, ".exc");
            }
        };
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
     * Volgorde: lokaal → globaal → constante → SSA-undef-fallback.
     */
    public LLVMValueRef resolveValue(IRValue value) {
        return switch (value) {
            case IRValue.Temp t -> {
                LLVMValueRef v = localValueMap.get(t.name());
                if (v == null) v = globalValueMap.get(t.name());
                if (v == null) {
                    // Cross-block SSA-waarde waarvan de definiërende
                    // instructie pas in een later (of niet-taken) blok
                    // staat: het ASM-backend draait dit via slot-allocatie;
                    // in deze fase volstaan we met een `undef` zodat de
                    // batch compileert (per JLS-semantiek is de waarde ook
                    // in het niet-taken pad ongedefinieerd).
                    yield LLVMGetUndef(types.map(t.type()));
                }
                yield v;
            }
            case IRValue.Named n -> {
                LLVMValueRef v = globalValueMap.get(n.name());
                if (v == null) {
                    // Legacy owner-in-name-codeing (wat het ASM-backend via
                    // resolveOwnerForNamed oplost): een `Named` zonder
                    // owner-fundamental. Zoek de global waarvan de key op
                    // `<owner>_<name>` (of `<name>` zelf) eindigt.
                    for (var entry : globalValueMap.entrySet()) {
                        if (entry.getKey().endsWith("$" + n.name())
                                || entry.getKey().endsWith("." + n.name())
                                || entry.getKey().endsWith("_" + n.name())
                                || entry.getKey().equals("@" + n.name())) {
                            v = entry.getValue();
                            break;
                        }
                    }
                }
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
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == null || arr[i].isNull()) {
                throw new IllegalStateException(
                        "arg " + i + " null voor call met args=" + args);
            }
        }
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

    /** Resolveert Values(obj, Named(...)) naar een GEP-pointer in het object-struct,
     * of {@code null} als de klasse geen layout heeft (bibliotheekklasse buiten
     * deze compilatie) — dan kan de caller degraderen in plaats van falen. */
    private LLVMValueRef resolveObjectFieldPointer(IRValue value) {
        IRValue.Values values = (IRValue.Values) value;
        var list = values.values();
        IRValue.Named named = (IRValue.Named) list.getLast();

        // receiver-object = alles vóór de Named
        LLVMValueRef obj = resolveValue(list.get(0));

        ClassLayouts.ClassLayout layout;
        int fieldIndex = named.fieldIndex();
        final var directOwner = classLayouts.getFor(named.ownerType());
        if (directOwner != null && fieldIndex >= 0) {
            layout = directOwner;
        } else {
            // fieldIndex == -1: de frontend gaf geen teller mee (Nabu-source
            // velden). Zoek de index dan op veldnaam: eerst binnen de
            // eigenaar-module, anders in de hele batch (dan declareert een
            // andere klasse het veld en levert dat de layout).
            layout = directOwner;
            final int byName = classLayouts.fieldIndexIn(
                    classLayouts.moduleOf(directOwner != null
                            ? directOwner.internalName : null),
                    named.name());
            if (byName >= 0) {
                    fieldIndex = byName;
            } else {
                final var fieldOwner = classLayouts.findField(named.name(), fieldIndex);
                if (fieldOwner == null) {
                    // Bibliotheekklasse buiten deze compilatie: we kennen geen
                    // layout en dus geen veld-offset. Dat is een beperking van
                    // de backend, geen fatale compilatiefout.
                    warnUnsupportedField(named, "geen layout voor de eigenaar");
                    return null;
                }
                layout = fieldOwner.layout();
                fieldIndex = fieldOwner.fieldIndex();
            }
        }
        int structIndex = layout.structIndex(fieldIndex);
        if (structIndex < 0) {
            // De teller-index uit het IR is een HINT: de frontend telt over
            // getEnclosedElements(), terwijl de IRModule ook synthetische
            // velden kan bevatten (bv. this$0 van een inner class). Dan klopt
            // de index niet en is de veldnaam leidend: eerst binnen de
            // layout-module, anders via de declarerende klasse in de batch.
            final int byName = classLayouts.fieldIndexIn(
                    classLayouts.moduleOf(layout.internalName), named.name());
            if (byName >= 0) {
                structIndex = layout.structIndex(byName);
            }
        }
        if (structIndex < 0) {
            final var declaring = classLayouts.findField(named.name(), -1);
            if (declaring != null) {
                final int alt = declaring.layout().structIndex(declaring.fieldIndex());
                if (alt >= 0) {
                    layout = declaring.layout();
                    structIndex = alt;
                }
            }
        }
        if (structIndex < 0) {
            warnUnsupportedField(named, "onbekende veld-offset in " + layout.internalName
                    + " (velden: [" + classLayouts.fieldDiagnostics(layout.internalName) + "])");
            return null;
        }

        LLVMValueRef fieldPtr = LLVMBuildStructGEP2(builder, layout.structType, obj, structIndex,
                ".field." + named.name());
        return fieldPtr;
    }

    /**
     * Meldt één keer per veld dat een veldtoegang niet benaderbaar is, omdat
     * de declarerende klasse buiten deze compilatie ligt. De toegang degradeert
     * dan naar {@code undef} (lezen) of een no-op (schrijven): het object is
     * immers exact één header groot, dus schrijven zou geheugen beschadigen.
     */
    private void warnUnsupportedField(IRValue.Named named, String reason) {
        if (!warnedUnsupportedFields.add(named.name() + "@" + named.fieldIndex())) {
            return;
        }
        System.err.println("[nabu/LLVM] waarschuwing: veld '" + named.name()
                + "' (index " + named.fieldIndex() + ") is niet benaderbaar — " + reason
                + ". Het veld levert een ongedefinieerde waarde; voeg de klasse toe aan de"
                + " sources (of vermijd het veld) voor echte veldtoegang.");
    }
}


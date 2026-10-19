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
    /** Name -> global found by the suffix scan in {@link #resolveValue}. */
    private final Map<String, LLVMValueRef>   suffixMatches = new HashMap<>();
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
    // Per call-site-shape (naam, parameters, resultaattype) gedeclareerde
    // externen voor signature-polymorfe methoden.
    private final Map<String, LLVMValueRef> polymorphicDeclarations = new HashMap<>();
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
                "Unsupported instruction: " + instr.getClass().getSimpleName());
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

        // Bytecode rekent met boolean/byte/char/short als int: de operanden
        // van één instructie kunnen daardoor in breedte verschillen
        // (`icmp eq i1 %x, i32 0`) en een referentie wordt met de constante 0
        // vergeleken. LLVM eist identieke operand-types.
        LLVMTypeRef leftType  = LLVMTypeOf(left);
        LLVMTypeRef rightType = LLVMTypeOf(right);
        if (isIntegerType(leftType) && isIntegerType(rightType)) {
            int lw = LLVMGetIntTypeWidth(leftType);
            int rw = LLVMGetIntTypeWidth(rightType);
            final boolean leftUnsigned = isUnsigned(op.left());
            final boolean rightUnsigned = isUnsigned(op.right());
            if ((leftUnsigned || rightUnsigned) && lw > 1 && rw > 1 && lw < 32 && rw < 32) {
                // Een char rekent en vergelijkt als int (JLS 5.6.2): twee i16's
                // vergeleken met slt maakten van een char >= 0x8000 een
                // negatief getal.
                LLVMTypeRef i32 = LLVMInt32TypeInContext(ctx);
                left = coerceInt(left, i32, leftUnsigned);
                right = coerceInt(right, i32, rightUnsigned);
            } else if (lw < rw) left = coerceInt(left, rightType, leftUnsigned);
            else if (rw < lw) right = coerceInt(right, leftType, rightUnsigned);
        } else if (isPointerType(leftType) && isIntegerType(rightType)) {
            right = intToPointer(right, leftType);
        } else if (isIntegerType(leftType) && isPointerType(rightType)) {
            left = intToPointer(left, rightType);
        }

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
            // Java-semantiek: >> is arithmetic (teken-uitbreidend, ASHR),
            // >>> is logical (nul-vullend, LSHR). De JVM maskeert het
            // shift-bedrag impliciet (int: &0x1F, long: &0x3F); LLVM's
            // shl/ashr/lshr doen dat NIET automatisch voor een te groot
            // shift-bedrag, maar reële Java-bytecode respecteert die
            // maskering altijd al op bronniveau (de JVM-spec garandeert
            // het), dus een expliciete mask hier is niet nodig voor
            // geldige invoer.
            case SHL  -> LLVMBuildShl (builder, left, right, name);
            case SHR  -> LLVMBuildAShr(builder, left, right, name);
            case USHR -> LLVMBuildLShr(builder, left, right, name);
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

    /**
     * Een concat-operand die al een String is. Elke referentie is een
     * {@code ptr i8}; alleen een descriptor zegt of het een String is. Een
     * {@code Integer} uit {@code Integer.valueOf} ging anders ongewijzigd als
     * "string" naar nabu_concat. Zonder descriptor (string-literal, null):
     * als String behandelen, zoals voorheen.
     */
    private boolean isStringOperand(IRType t) {
        if (!isStringType(t)) return false;
        final String descriptor = ((IRType.Ptr) t).jvmDescriptor();
        return descriptor == null || descriptor.equals("Ljava/lang/String;");
    }

    private void emitStringConcat(IRInstruction.BinaryOp op) {
        // Java: "" + x is String.valueOf(x); als geen van beide operanden
        // zeker een String is (bv. twee referenties zonder descriptor) blijft
        // de oude behandeling gelden.
        final boolean leftString = isStringOperand(op.left().type());
        final boolean rightString = isStringOperand(op.right().type());
        final boolean anyString = leftString || rightString;
        LLVMValueRef left  = anyString && !leftString ? toConcatOperand(op.left(), true) : toConcatOperand(op.left(), false);
        LLVMValueRef right = anyString && !rightString ? toConcatOperand(op.right(), true) : toConcatOperand(op.right(), false);
        LLVMValueRef fn = globalValueMap.get("@nabu_concat");
        if (fn == null)
            throw new IllegalStateException("Runtime helper @nabu_concat not defined");

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
    private LLVMValueRef toConcatOperand(IRValue operand, boolean asObject) {
        IRType type = operand.type();
        if (isStringType(type) && !asObject) {
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
                throw new IllegalStateException("Runtime helper @nabu_bool_to_string not defined");
            }
            PointerPointer<Pointer> boolArgs = new PointerPointer<>(1);
            boolArgs.put(0, LLVMBuildZExt(builder, resolveValue(operand),
                    LLVMInt32TypeInContext(ctx), new BytePointer(".toint")));
            Reference.reachabilityFence(boolArgs);
            return LLVMBuildCall2(builder, LLVMGlobalGetValueType(boolFn), boolFn, boolArgs, 1,
                    ".tostr");
        } else if (isArrayReceiver(operand)) {
            // Een array heeft geen header/vtable (Java gaf "[C@1b6d3586").
            return constants.resolveJavaString("");
        } else if (type instanceof IRType.Ptr) {
            // Object-referentie: String.valueOf(obj) — "null" of de virtuele
            // toString(). Was een lege string, waardoor bv. "" + set leeg bleef.
            fn = globalValueMap.get("@nabu_object_to_string");
        } else {
            throw new IllegalStateException(
                    "String concat with a non-string, non-primitive operand: " + type
                            + " (value: " + nameOf(operand) + ")");
        }
        if (fn == null) {
            throw new IllegalStateException(
                    "Runtime helper for " + type + " -> string not defined");
        }
        LLVMValueRef value = resolveValue(operand);
        if (type instanceof IRType.Int intType && intType.bits() < 32) {
            // nabu_int_to_string neemt i32, maar char/byte/short zijn hier
            // nog smal. Een char verbreedt zonder teken, byte/short met.
            value = coerceInt(value, LLVMInt32TypeInContext(ctx), intType.unsigned());
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
        // Altijd in het entry-blok: de bytecode-lowering maakt het slot van
        // een lokale variabele aan waar die voor het eerst geschreven wordt,
        // en dat blok domineert lang niet elk later gebruik ("Instruction
        // does not dominate all uses"). Een slot in het entry-blok domineert
        // de hele functie (en mem2reg promoveert alleen entry-allocas).
        LLVMBasicBlockRef current = LLVMGetInsertBlock(builder);
        LLVMBasicBlockRef entry = LLVMGetEntryBasicBlock(LLVMGetBasicBlockParent(current));
        LLVMValueRef firstInstr = LLVMGetFirstInstruction(entry);
        boolean hoist = firstInstr != null && !firstInstr.isNull();
        if (hoist) {
            LLVMPositionBuilder(builder, entry, firstInstr);
        }
        LLVMValueRef ptr = LLVMBuildAlloca(builder,
                types.map(a.allocType()),
                new BytePointer(nameOf(a.result())));
        if (hoist) {
            LLVMPositionBuilderAtEnd(builder, current);
        }
        storeLocal(a.result(), ptr);
    }

    private static boolean isIntegerType(LLVMTypeRef type) {
        return LLVMGetTypeKind(type) == LLVMIntegerTypeKind;
    }

    private static boolean isFloatingType(LLVMTypeRef type) {
        int kind = LLVMGetTypeKind(type);
        return kind == LLVMFloatTypeKind || kind == LLVMDoubleTypeKind;
    }

    private static boolean isPointerType(LLVMTypeRef type) {
        return LLVMGetTypeKind(type) == LLVMPointerTypeKind;
    }

    /**
     * Past een integer aan naar {@code target}: i1 (boolean) wordt
     * nul-uitgebreid, de rest teken-uitgebreid of afgekapt. LET OP: char en
     * short delen in de IR het type i16, dus een char >= 0x8000 wordt hier
     * (net als in {@link #emitCast}) ten onrechte teken-uitgebreid.
     */
    private LLVMValueRef coerceInt(LLVMValueRef value, LLVMTypeRef target) {
        return coerceInt(value, target, false);
    }

    /** Als {@link #coerceInt(LLVMValueRef, LLVMTypeRef)}; {@code unsigned} (char) verbreedt met nullen. */
    private LLVMValueRef coerceInt(LLVMValueRef value, LLVMTypeRef target, boolean unsigned) {
        int from = LLVMGetIntTypeWidth(LLVMTypeOf(value));
        int to = LLVMGetIntTypeWidth(target);
        if (from == to) return value;
        if (from > to) return LLVMBuildTrunc(builder, value, target, ".trunc");
        return from == 1 || unsigned
                ? LLVMBuildZExt(builder, value, target, ".zext")
                : LLVMBuildSExt(builder, value, target, ".sext");
    }

    /** Of de IR-waarde een char is (een i16 die zonder teken verbreedt). */
    private static boolean isUnsigned(final IRValue value) {
        return value != null && value.type() instanceof IRType.Int intType && intType.unsigned();
    }

    /** Een integer die als referentie gebruikt wordt (in bytecode: de 0 van ifnull). */
    private LLVMValueRef intToPointer(LLVMValueRef value, LLVMTypeRef pointerType) {
        if (LLVMIsConstant(value) != 0 && LLVMIsNull(value) != 0) {
            return LLVMConstPointerNull(pointerType);
        }
        return LLVMBuildIntToPtr(builder, value, pointerType, ".toptr");
    }

    /** Past {@code value} aan naar {@code target} als beide integers zijn. */
    private LLVMValueRef coerceTo(LLVMValueRef value, LLVMTypeRef target) {
        return coerceTo(value, target, false);
    }

    private LLVMValueRef coerceTo(LLVMValueRef value, LLVMTypeRef target, boolean unsigned) {
        LLVMTypeRef actual = LLVMTypeOf(value);
        if (isIntegerType(actual) && isIntegerType(target)) {
            return coerceInt(value, target, unsigned);
        }
        if (isIntegerType(actual) && isPointerType(target)
                && LLVMIsConstant(value) != 0 && LLVMIsNull(value) != 0) {
            return LLVMConstPointerNull(target);
        }
        return value;
    }

    private void emitAllocaArray(IRInstruction.AllocaArray a) {
        // Layout: [i32 length][ element 0 ][ element 1 ]...
        // Totale bytes = ARRAY_HEADER_SIZE + count * elemSize
        LLVMValueRef count = resolveValue(a.size());
        long elemSize = arrayElementSize(a.allocType());
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
        final String arrayClass = arrayClassName(a);
        storeArrayElemSize(array, elemSize | ((long) (arrayClass != null ? arrayTypeId(arrayClass) : 0) << 8));
        storeLocal(a.result(), array);
        refCounts.onAlloc(a.result(), array);
    }

    /**
     * JVM names of the array types this module allocates, by index (index 0
     * is "unknown"). The index goes into the upper 24 bits of the array's
     * element-size word, so the runtime knows the class of an array
     * ({@code nabu_array_class}); LLVMModuleEmitter emits the table.
     */
    private final Map<String, Integer> arrayTypeIds = new java.util.LinkedHashMap<>();

    private int arrayTypeId(final String arrayClass) {
        return arrayTypeIds.computeIfAbsent(arrayClass, k -> arrayTypeIds.size() + 1);
    }

    /** The array type names in index order, starting at index 1. */
    List<String> arrayTypeNames() {
        return new ArrayList<>(arrayTypeIds.keySet());
    }

    /**
     * Bytes per element. The AllocaArray carries the array type (source and
     * bytecode IR alike), whose descriptor says the element: `[I` 4, `[J` 8,
     * `[Z`/`[B` 1, a reference 8. The pointer size of the array type itself
     * gave every source array 8-byte elements in the header, while its
     * elements are stored 4 bytes apart (an int[]): System.arraycopy then
     * copied twice the bytes. A plain element type (direct IR) still works.
     */
    private long arrayElementSize(final IRType allocType) {
        if (allocType instanceof IRType.Ptr ptr && ptr.jvmDescriptor() != null
                && ptr.jvmDescriptor().startsWith("[") && ptr.jvmDescriptor().length() > 1) {
            return switch (ptr.jvmDescriptor().charAt(1)) {
                case 'Z', 'B' -> 1;
                case 'C', 'S' -> 2;
                case 'I', 'F' -> 4;
                case 'J', 'D' -> 8;
                default -> sizeOf(LLVMPointerTypeInContext(ctx, 0));
            };
        }
        return sizeOf(types.map(allocType));
    }

    /** The JVM name of the allocated array type ("[Ljava/lang/String;", "[I"), or null. */
    private static String arrayClassName(final IRInstruction.AllocaArray a) {
        if (a.allocType() instanceof IRType.Ptr ptr
                && ptr.jvmDescriptor() != null && ptr.jvmDescriptor().startsWith("[")) {
            return ptr.jvmDescriptor();
        }
        final String element = switch (a.allocType()) {
            case IRType.Ptr ptr when ptr.jvmDescriptor() != null
                    && (ptr.jvmDescriptor().startsWith("L") || ptr.jvmDescriptor().startsWith("[")) -> ptr.jvmDescriptor();
            case IRType.Bool ignored -> "Z";
            case IRType.Float f -> f.bits() == 32 ? "F" : "D";
            case IRType.Int i -> switch (i.bits()) {
                case 8 -> "B";
                case 16 -> i.unsigned() ? "C" : "S";
                case 64 -> "J";
                case 32 -> "I";
                default -> null;
            };
            default -> null;
        };
        return element != null ? "[" + element : null;
    }

    /**
     * {@code array.getClass()}: an array has no object header, so the class
     * comes from the runtime ({@code nabu_array_class}, from the element-type
     * index in the header). Object.getClass in C took the array's length for
     * a type pointer and answered String.
     */
    private boolean tryEmitArrayGetClass(final IRInstruction.Call call) {
        if (!"java.lang.Object_getClass".equals(call.function()) || call.args().isEmpty()
                || !isArrayReceiver(call.args().get(0))) {
            return false;
        }
        final LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        final PointerPointer<Pointer> params = new PointerPointer<>(1);
        params.put(0, ptrType);
        final LLVMTypeRef fnType = LLVMFunctionType(ptrType, params, 1, 0);
        LLVMValueRef fn = globalValueMap.get("@nabu_array_class");
        if (fn == null || fn.isNull()) {
            final LLVMModuleRef mod = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)));
            fn = LLVMGetNamedFunction(mod, "nabu_array_class");
            if (fn == null || fn.isNull()) {
                fn = LLVMAddFunction(mod, "nabu_array_class", fnType);
                LLVMSetLinkage(fn, LLVMExternalLinkage);
            }
            globalValueMap.put("@nabu_array_class", fn);
        }
        final PointerPointer<Pointer> args = new PointerPointer<>(1);
        args.put(0, resolveValue(call.args().get(0)));
        final LLVMValueRef result = emitCallOrInvoke("nabu_array_class", fnType, fn, args, 1, call.result());
        if (call.result() != null) {
            storeLocal(call.result(), result);
        }
        return true;
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

    /**
     * Extra bytes ná de object-header voor het runtime-state-slot van
     * library-objecten (offset 40, vijfde void*-woord).
     *
     * <p>De backend kent de instancevelden van niet-gecompileerde klassen
     * niet — veldtoegang loopt volledig via de runtime ({@code
     * java.lang.reflect.Field_getName(self)} enz.), die de bijbehorende C-struct dan
     * ook zelf moet onderbrengen. Die struct hangt aan het object via dit
     * slot; {@code nabu_init_object} zet het leeg. Grootte niet aanpassen
     * zonder {@code runtime/nabu_runtime.h} en {@code runtime/jcl_lite.c}
     * mee te nemen.
     */
    private static final long JCL_STATE_SLOT = 8;

    private void runHeapAllocFallback(IRInstruction.HeapAlloc ha) {
        // Onbekende (library) klassen bv. java/lang/Exception: de backend kent
        // geen layout, dus de runtime kan de object-header niet voor ons neerzetten
        // — die moet in IR gebeuren. Wij reserveren daarom zelf geheugen, met de
        // allocator van de gekozen strategie, en geven het aan de runtime die de
        // type-info in de header zet (`i8* nabu_init_object(i8* obj, i8* name)`).
        //
        // De grootte is één header plus het state-slot: meer instancevelden
        // weten we niet, die levert de runtime in haar eigen struct.
        final long headerSize = computeSizeOf(classLayouts.objectHeaderType());

        LLVMValueRef obj;
        if (ha.allocType() instanceof IRType.Ptr p && p.jvmDescriptor() != null) {
            final String internalName = ClassLayouts.fromDescriptor(p.jvmDescriptor());
            obj = allocForStrategy(headerSize + JCL_STATE_SLOT, nameOf(ha.result()));
            // GC-strategie-onafhankelijk: het runtime-type-info komt van de naam.
            final LLVMValueRef initObjFn = globalValueMap.get("@nabu_init_object");
            if (initObjFn == null) {
                throw new IllegalStateException("Runtime helper @nabu_init_object not defined");
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
                    throw new IllegalStateException("Allocation helper not defined");
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

    /**
     * array[1] (i32) = grootte van één element in bytes. De runtime kent het
     * elementtype van een array niet, maar generieke kopieën
     * ({@code System.arraycopy}, {@code clone}) moeten weten hoeveel bytes een
     * element beslaat: met een vaste 8 schreef een kopie van een {@code byte[]}
     * acht keer voorbij het einde van de doel-array (heap-corruptie zodra
     * String/StringBuilder uit JDK-bytecode hun byte[]-buffers kopiëren).
     */
    private void storeArrayElemSize(LLVMValueRef array, long elemSize) {
        PointerPointer<Pointer> idx = new PointerPointer<>(1);
        idx.put(0, LLVMConstInt(LLVMInt64TypeInContext(ctx), 1, 0));
        LLVMValueRef slot = LLVMBuildGEP2(builder, LLVMInt32TypeInContext(ctx),
                array, idx, 1, ".esize.ptr");
        LLVMBuildStore(builder, LLVMConstInt(LLVMInt32TypeInContext(ctx), elemSize, 0), slot);
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
            throw new IllegalStateException("Runtime helper @nabu_monitorenter not defined");

        PointerPointer<Pointer> args = new PointerPointer<>(1);
        args.put(0, obj);

        LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);
        LLVMBuildCall2(builder, fnType, fn, args, 1, "");
    }

    private void emitMonitorExit(IRInstruction.MonitorExit mx) {
        LLVMValueRef obj = resolveValue(mx.object());
        LLVMValueRef fn = globalValueMap.get("@nabu_monitorexit");
        if (fn == null)
            throw new IllegalStateException("Runtime helper @nabu_monitorexit not defined");

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
                // Onbekend veld (bibliotheek/layout): geen load bouwen;
                // het resultaat wordt als undef behandeld om verdere
                // emissie niet te laten crashen.
                if (load.result() != null) {
                    storeLocal(load.result(), LLVMGetUndef(types.map(load.result().type())));
                }
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
            // resolveValue kan via de suffix-fallback een FUNCTIE teruggeven
            // (globalValueMap bevat functies en globals door elkaar). Het
            // value-type is dan een functietype; een load daarvan laat LLVM
            // (release-build, llvm_unreachable) de JVM hard crashen.
            LLVMValueRef asGlobalVar = LLVMIsAGlobalVariable(global);
            if (asGlobalVar == null || asGlobalVar.isNull()) {
                throw new IllegalStateException(
                        "Static field " + namedStatic.name()
                                + " (owner=" + namedStatic.ownerType()
                                + ") does not resolve to a global variable but to: "
                                + LLVMGetValueName(global).getString());
            }
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
                        "No phi slot alloca for: " + t.name());
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
                    "emitLoad expects a pointer type, but got: "
                            + ptrType + " for value: " + nameOf(target)
                            + "\nTip: parameters are not pointers — use them directly "
                            + "or store them via emitAlloca + emitStore.");
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
        LLVMValueRef value = resolveValue(store.value());
        LLVMValueRef slot = LLVMIsAAllocaInst(ptr);
        if (slot != null && !slot.isNull()) {
            // Een stackslot heeft één vast type (bv. het i32-merge-slot van
            // de operand-stack), de geschreven waarde kan smaller zijn
            // (boolean/byte/char/short): zonder aanpassing blijven de hoge
            // bits van het slot ongedefinieerd.
            value = coerceTo(value, LLVMGetAllocatedType(slot), isUnsigned(store.value()));
        } else {
            // Hetzelfde voor een statisch veld (global) en een objectveld: de
            // bytecode zet een boolean/byte/char/short als int (`iconst_1;
            // putstatic COMPACT_STRINGS`). Een i32-store in een i1-global
            // schreef 4 bytes en overschreef de buren.
            final LLVMValueRef global = LLVMIsAGlobalVariable(ptr);
            if (global != null && !global.isNull()) {
                value = coerceTo(value, LLVMGlobalGetValueType(global), isUnsigned(store.value()));
            } else if (isObjectFieldAccess(target)) {
                value = coerceTo(value, types.map(objectFieldPointeeType(target)), isUnsigned(store.value()));
            }
        }
        LLVMBuildStore(builder, value, ptr);
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

    private void emitCall(IRInstruction.Call rawCall) {
        // Super-constructor-aanroep (super(...)): de <Class>_super-call is een
        // frontend-bridge. Als de super-klasse in deze compilatie zit, emitteer
        // een DIRECTE call naar diens <Class>_init (args bevatten al 'this' als
        // eerste parameter); anders blijft het een no-op (de super heeft dan
        // geen native state die hier geïnitialiseerd moet worden).
        if (rawCall.function().endsWith("_super")) {
            tryEmitSuperConstructor(rawCall);
            return;
        }

        // Array-clone met eigenaar-owner -> generiek Object_clone-symbool
        // (zie CallTargets); vóór de dispatch-routes, want een array heeft
        // geen vtable.
        final IRInstruction.Call call = classLayouts.canonicalize(CallTargets.normalize(rawCall));

        if (tryEmitArrayGetClass(call)) {
            return;
        }

        // Virtuele dispatch: als de receiver een bekende klasse is met een
        // vtable-slot voor deze methode, roepen we indirect via de vtable aan.
        if (call.callKind() == CallKind.VIRTUAL && tryEmitVirtualCall(call)) {
            return;
        }
        // Een Object-methode via een interface-type (`invokeinterface
        // Appendable.toString`, JLS §9.2): geen itable-slot, maar elke vtable
        // begint met die van Object. Zonder dit werd het een directe call naar
        // Object.toString, en gaf Formatter.toString "java/lang/StringBuilder@…"
        // in plaats van de opgebouwde tekst.
        if (call.callKind() == CallKind.INTERFACE && call.function() != null
                && call.function().startsWith("java.lang.Object_")
                && tryEmitVirtualCall(call)) {
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
        // An invokevirtual of a method the class only inherits from an
        // interface (`AbstractImmutableList.contains` calls `this.indexOf`,
        // which only List declares): canonicalize maps it to the interface
        // method, which has no vtable slot. Dispatch through the itable as for
        // invokeinterface; a direct call went to the abstract declaration (a
        // stub, or a jcl-lite C version for C collections).
        if (call.callKind() == CallKind.VIRTUAL && classLayouts.ownedByInterface(call.function())
                && tryEmitInterfaceCall(call)) {
            return;
        }

        LLVMValueRef fn = null;
        if (isConstructorCall(call)) {
            // Een constructor die met een methode van dezelfde naam botst, staat
            // onder een eigen symbool: de gewone naam is die van de methode.
            // De parameterlijst is nodig om de juiste overload te kiezen:
            // overloads delen de IR-functienaam.
            if (hasExplicitReceiver(call)) {
                // Bytecode-conventie: de receiver staat in de operanden en de
                // paramTypes zijn exact die van de descriptor. De declaratie
                // staat onder precies die sleutel (zonder receiver), dus eerst
                // exact zoeken: de naam-gebaseerde lookup hieronder kiest bij
                // meerdere overloads met hetzelfde soort-profiel een
                // willekeurige, of vindt niets en laat een extern achter.
                fn = globalValueMap.get("@" + FnKeys.fnKey(
                        call.function(), call.paramTypes()));
                if (!arityMatches(fn, emittedArgCount(call))) {
                    fn = null;
                }
            }
            if (fn == null || fn.isNull()) {
                fn = lookupDeclared(call.function(), callParamsWithReceiver(call),
                        call.returnType());
            }
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
                fn = lookupExternalInstance(call.function(), call);
            }
            // Eerst de overload-bewuste lookup, dan pas de kale naam: onder de
            // kale naam staat de EERST gedeclareerde overload, en die bond
            // elke aanroep waarvan de exacte sleutel miste (bv.
            // `isNonDefault(String[], String[])` aan `isNonDefault(Object,
            // Object)`).
            if (fn == null || fn.isNull()) {
                // De functie is wél in deze compilatie gedeclareerd, maar dan onder
                // de simple-owner naam terwijl de call de gekwalificeerde naam
                // gebruikt (bv. 'package.Owner_method' -> 'Owner_method').
                fn = lookupDeclared(call.function(), callParamsWithReceiver(call),
                        emittedArgCount(call), call.returnType());
            }
            if (fn == null || fn.isNull()) {
                // Legacy-lookups (bestaande gedeclareerden zonder overload-sleutel)
                fn = globalValueMap.get("@" + call.function());
                if (!arityMatches(fn, emittedArgCount(call))) {
                    fn = null;
                }
            }
        }
        if (fn == null || fn.isNull()) {
            if (isConstructorCall(call)) {
                fn = declareExternalConstructor(call);
            } else {
                fn = lookupInherited(call);
                if ((fn == null || fn.isNull()) && isSignaturePolymorphic(call.function())) {
                    fn = declareSignaturePolymorphic(call);
                }
            }
        }
        if (fn != null && !fn.isNull() && isSignaturePolymorphic(call.function())
                && !returnTypeMatches(fn, call)) {
            // Zelfde parameters, ander resultaattype: bij een signature-
            // polymorfe methode hoort het resultaattype bij de call-site.
            fn = declareSignaturePolymorphic(call);
        }
        if ((fn == null || fn.isNull()) && !isConstructorCall(call)) {
            // De methode is nergens in deze compilatie gedeclareerd met dit
            // parameterprofiel: typisch een methode die geërfd is van een
            // klasse buiten de sluiting (bv. ClassLoader.definePackage via
            // BuiltinClassLoader). Een extern voor precies deze call-site;
            // de runtime of een stub levert het symbool.
            fn = declareSignaturePolymorphic(call);
            if (fn != null && !fn.isNull()) {
                System.err.println("[nabu/LLVM] warning: " + call.function()
                        + " is not declared with parameters " + call.paramTypes()
                        + " (called in " + enclosingFunctionName()
                        + "); declared as an external symbol.");
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
                    "Unknown function: " + FnKeys.fnKey(call.function(), diagParams)
                            + "\n  in IR function : " + enclosingFunctionName()
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
            if (hasExplicitReceiver(call)) {
                // De receiver staat al vooraan; alleen de bijbehorende
                // openstaande HeapAlloc afboeken.
                pendingConstructorAllocs.removeLastOccurrence(call.args().getFirst());
            } else {
                callArgs = prependThis(call.function(), call.args());
            }
        }

        // De C-runtime-helpers (nabu_instanceof, nabu_reflect_*, ...) krijgen
        // type- en veldnamen als const char*; alleen nabu_concat werkt op
        // Java-strings.
        final boolean runtimeHelper = call.function().startsWith("nabu_")
                && !call.function().equals("nabu_concat");
        constants.setRawStrings(runtimeHelper);
        PointerPointer<Pointer> args;
        try {
            args = buildArgs(callArgs);
        } finally {
            constants.setRawStrings(false);
        }
        int argCount = callArgs.size();

        // Vangnet voor de naam-gebaseerde fallbacks hierboven: die kiezen bij
        // een gemiste overload-sleutel de eerste overload met evenveel
        // parameters. Passen de werkelijke operanden daar qua soort
        // (integer/floating/pointer) niet op, dan een overload van dezelfde
        // naam zoeken waar ze wél op passen (bv. `Byte.valueOf(String)` dat
        // aan `valueOf(byte)` bond).
        if (fnType != null && !fnType.isNull() && !argKindsMatch(fnType, args, argCount)) {
            LLVMValueRef alternative = findOverloadByKinds(call.function(), args, argCount);
            if (alternative != null) {
                fn = alternative;
                fnType = LLVMGlobalGetValueType(alternative);
            }
        }

        if (fnType == null || fnType.isNull()) {
            throw new IllegalStateException(
                    "fnctype null for " + call.function()
                            + " paramTypes=" + call.paramTypes());
        }
        if (fn == null || fn.isNull()) {
            throw new IllegalStateException("fn null for " + call.function());
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

        // Bezette naam (ctor-overload)? Deterministische suffix i.p.v. LLVM's
        // volgnummer-unieking — dezelfde stabiele symbolen als in
        // declareSignature; zie FnKeys.stableSymbol.
        final String symbol = FnKeys.stableSymbol(mod, name, callParams);
        LLVMValueRef ext = LLVMAddFunction(mod, new BytePointer(symbol), fnType);
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

    private static boolean isArrayReceiver(final IRValue receiver) {
        final IRType type = receiver.type();
        return type instanceof IRType.Array
                || (type instanceof IRType.Ptr p && p.jvmDescriptor() != null && p.jvmDescriptor().startsWith("["));
    }

    /** Een array-clone die CallTargets naar java.lang.Object_clone omlegde. */
    private boolean isArrayCallee(final IRInstruction.Call call) {
        return "java.lang.Object_clone".equals(call.function())
                && !call.args().isEmpty()
                && isArrayReceiver(call.args().get(0));
    }

    private boolean tryEmitVirtualCall(IRInstruction.Call call) {
        if (call.args().isEmpty()) return false;

        IRValue receiver = call.args().get(0);
        // Een array heeft geen object-header en dus geen vtable: `arr.clone()`
        // (ook binnen Arrays.copyOf) gaat direct naar de runtime. Sinds
        // Object.clone een vtable-slot heeft, las dispatch anders arraydata als
        // vtable-pointer.
        if (isArrayReceiver(receiver) || isArrayCallee(call)) return false;
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
        if (slot < 0) {
            // The receiver's IR type can be wrong for bytecode: javac reuses a
            // local slot (a caught exception, later an InputStream), and the
            // load keeps the type of the first. The method's own class then
            // gives the slot (its subclasses keep the index). A direct call
            // went to the body-less declaration of InputStream.close, which
            // is `unreachable`, instead of ByteArrayInputStream.close.
            final ClassLayouts.ClassLayout declaring = classLayouts.findLayoutForVtableSlot(
                    call.function(), slotParams);
            if (declaring != null && declaring.vtableGlobal != null && !declaring.isInterface) {
                final int declaringSlot = declaring.vtableSlot(call.function(), slotParams);
                if (declaringSlot >= 0) {
                    layout = declaring;
                    slot = declaringSlot;
                }
            }
        }
        if (slot < 0) return false;

        LLVMTypeRef ptrType = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);

        emitReceiverNullCheck(obj, call.function());
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
        List<IRType> fnParams = indirectCallParams(call);
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
        // Eerst de klasse-gekwalificeerde sleutel (dezelfde als
        // FunctionEmitter.emitBody gebruikt): de kale sleutel is gedeeld door
        // elke klasse met dezelfde simple naam. picocli heeft zeven geneste
        // klassen 'Builder'; 'Builder_init#' wees daardoor naar de laatst
        // gedeclareerde, en `new RegexTransformer.Builder()` draaide de
        // constructor van een andere Builder.
        LLVMValueRef value = null;
        final String qualified = classLayouts.qualifiedName(declared);
        if (qualified != null) {
            final String q = symbol.equals(declared.name)
                    ? qualified
                    : qualified + symbol.substring(declared.name.length());
            value = globalValueMap.get("@" + FnKeys.fnKey(q, declaredParams));
        }
        if (value == null || value.isNull()) {
            value = globalValueMap.get("@" + FnKeys.fnKey(symbol, declaredParams));
        }
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

    /** Of de operanden qua soort (integer/floating/pointer) op {@code fnType} passen. */
    private boolean argKindsMatch(final LLVMTypeRef fnType,
                                  final PointerPointer<Pointer> args,
                                  final int argCount) {
        if (LLVMGetTypeKind(fnType) != LLVMFunctionTypeKind
                || LLVMCountParamTypes(fnType) != argCount) {
            return false;
        }
        if (argCount == 0) return true;
        final PointerPointer<LLVMTypeRef> paramTypes = new PointerPointer<>(argCount);
        LLVMGetParamTypes(fnType, paramTypes);
        for (int i = 0; i < argCount; i++) {
            final LLVMTypeRef param = paramTypes.get(LLVMTypeRef.class, i);
            final LLVMTypeRef arg = LLVMTypeOf(new LLVMValueRef(args.get(i)));
            // Integers van verschillende breedte passen: die worden bij de
            // call aangepast (zie emitCallOrInvoke).
            if (isIntegerType(param) && isIntegerType(arg)) continue;
            if (LLVMGetTypeKind(param) != LLVMGetTypeKind(arg)) return false;
        }
        return true;
    }

    /**
     * Een functie die onder dezelfde naam (met een andere overload-sleutel of
     * symbool-suffix) geregistreerd is en waar de operanden op passen. De
     * sleutels worden gesorteerd doorlopen, zodat de keuze deterministisch is.
     */
    private LLVMValueRef findOverloadByKinds(final String function,
                                             final PointerPointer<Pointer> args,
                                             final int argCount) {
        final String bare = "@" + function;
        final List<String> keys = new ArrayList<>();
        for (final String key : globalValueMap.keySet()) {
            if (key.startsWith(bare) && key.length() > bare.length()
                    && (key.charAt(bare.length()) == '#' || key.charAt(bare.length()) == '$')) {
                keys.add(key);
            }
        }
        java.util.Collections.sort(keys);
        for (final String key : keys) {
            final LLVMValueRef candidate = globalValueMap.get(key);
            if (candidate == null || candidate.isNull()) continue;
            final LLVMValueRef asFunction = LLVMIsAFunction(candidate);
            if (asFunction == null || asFunction.isNull()) continue;
            if (argKindsMatch(LLVMGlobalGetValueType(candidate), args, argCount)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Geërfde methode: bytecode mag als eigenaar de SUBKLASSE noemen terwijl
     * de methode in een super-klasse gedeclareerd is (bv.
     * {@code BuiltinClassLoader.defineClass(String, ByteBuffer, CodeSource)},
     * die van {@code SecureClassLoader} komt). Loopt de super-keten af en
     * matcht alleen op de exacte overload-sleutel, zodat een gelijknamige
     * overload met een ander profiel nooit per abuis bindt.
     */
    private LLVMValueRef lookupInherited(IRInstruction.Call call) {
        String target = classLayouts.superCallTarget(call.function());
        while (target != null) {
            LLVMValueRef fn = globalValueMap.get("@" + FnKeys.fnKey(
                    target, call.paramTypes()));
            if (fn == null || fn.isNull()) {
                fn = globalValueMap.get("@" + FnKeys.fnKey(
                        target, callParamsWithReceiver(call)));
            }
            if (fn == null || fn.isNull()) {
                fn = lookupExternalInstance(target, call);
            }
            if (fn != null && !fn.isNull()
                    && arityMatches(fn, emittedArgCount(call))) {
                return fn;
            }
            target = classLayouts.superCallTarget(target);
        }
        return null;
    }

    /**
     * Externe (native/abstracte) instantie-methode: IRBuilder.
     * declareExternalFunction bewaart alleen de parametertypes, dus de
     * receiver heet daar {@code %arg0} en blijft — mét de descriptor van de
     * eigenaar — in de overload-sleutel staan (zie FnKeys.declaredParamsOf).
     * Een call-site sleutelt zonder receiver en miste zo elke overloaded
     * native (bv. {@code AbstractStackWalker.setContinuation(long, Object[],
     * Continuation)} naast {@code setContinuation(Continuation)}).
     */
    private LLVMValueRef lookupExternalInstance(final String target,
                                                final IRInstruction.Call call) {
        if (call.callKind() == CallKind.STATIC) return null;
        final String owner = classLayouts.ownerInternalName(target);
        if (owner == null) return null;
        final List<IRType> params = new ArrayList<>(call.paramTypes().size() + 1);
        params.add(new IRType.Ptr(IRType.I8, "L" + owner + ";"));
        params.addAll(call.paramTypes());
        final LLVMValueRef fn = globalValueMap.get("@" + FnKeys.fnKey(target, params));
        return arityMatches(fn, emittedArgCount(call)) ? fn : null;
    }

    /**
     * Signature-polymorfe methoden (JVMS §2.9.3): {@code MethodHandle.invoke}/
     * {@code invokeExact} en de access-mode-methoden van {@code VarHandle}.
     * Elke call-site draagt zijn EIGEN descriptor; de ene native declaratie
     * {@code (Object...)Object} past daar per definitie niet op.
     */
    private static boolean isSignaturePolymorphic(final String callTarget) {
        return callTarget != null
                && (callTarget.startsWith("java.lang.invoke.MethodHandle_")
                    || callTarget.startsWith("java.lang.invoke.VarHandle_"));
    }

    /**
     * Declareert een extern voor precies de shape van deze call-site (zelfde
     * route als de ad-hoc externen uit fase 2.5), zodat de module verifieert;
     * de runtime moet het symbool leveren.
     */
    private LLVMValueRef declareSignaturePolymorphic(final IRInstruction.Call call) {
        if (functionEmitter == null) return null;
        final String key = "@" + FnKeys.fnKey(call.function(), FnKeys.callParams(call));
        // Het resultaattype hoort bij de shape: `invokeBasic(Object)` komt
        // voor met een Object-, int- en void-resultaat.
        final String shape = key + "->" + call.returnType();
        LLVMValueRef fn = polymorphicDeclarations.get(shape);
        if (fn == null) {
            fn = globalValueMap.get(key);
            if (fn == null || fn.isNull() || !returnTypeMatches(fn, call)) {
                functionEmitter.declareSignature(call);
                fn = globalValueMap.get(key);
            }
            polymorphicDeclarations.put(shape, fn);
        }
        return arityMatches(fn, emittedArgCount(call)) ? fn : null;
    }

    /** Of het resultaattype van {@code fn} overeenkomt met dat van de call-site. */
    private boolean returnTypeMatches(final LLVMValueRef fn, final IRInstruction.Call call) {
        final LLVMTypeRef fnType = LLVMGlobalGetValueType(fn);
        if (LLVMGetTypeKind(fnType) != LLVMFunctionTypeKind) return true;
        final LLVMTypeRef declared = LLVMGetReturnType(fnType);
        final LLVMTypeRef expected = types.map(call.returnType());
        if (LLVMGetTypeKind(declared) != LLVMGetTypeKind(expected)) return false;
        return !isIntegerType(declared)
                || LLVMGetIntTypeWidth(declared) == LLVMGetIntTypeWidth(expected);
    }

    private static String firstNonNull(String first, String fallback) {
        return first != null ? first : fallback;
    }

    /**
     * De parameters {@code (this, paramTypes...)} van een indirecte (vtable-/
     * itable-) aanroep. Dekken de paramTypes van de call-site de operanden
     * niet (bv. doordat de receiver erin is meegeteld), dan zijn de operanden
     * zelf de enige consistente bron voor de signatuur.
     */
    private List<IRType> indirectCallParams(final IRInstruction.Call call) {
        final List<IRType> fnParams = new ArrayList<>(call.args().size());
        fnParams.add(new IRType.Ptr(IRType.I8));
        if (call.paramTypes().size() + 1 == call.args().size()) {
            fnParams.addAll(call.paramTypes());
            return fnParams;
        }
        System.err.println("[nabu/LLVM] warning: indirect call "
                + call.function() + " in " + enclosingFunctionName()
                + " has " + call.args().size() + " operands but paramTypes="
                + call.paramTypes() + "; signature derived from the operands.");
        for (int i = 1; i < call.args().size(); i++) {
            fnParams.add(call.args().get(i).type());
        }
        return fnParams;
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
        // The interface that declares the method decides, not the receiver's
        // IR type: that can be too specific or wrong (a parameter typed
        // List<IExitCodeGenerator> carried the type argument's descriptor, so
        // List.iterator looked like a call on IExitCodeGenerator, which has no
        // implementers, and became a direct call to List_iterator).
        ClassLayouts.ClassLayout layout = classLayouts.ownerLayout(call.function());
        if (layout == null || !layout.isInterface) {
            layout = classLayouts.getFor(receiver.type());
        }
        if (layout != null) {
            if (layout.isInterface && !classLayouts.hasImplementers(layout.internalName)) {
                // Geen enkele klasse in deze compilatie implementeert de
                // interface: elke itable van een runtime-instantie is daar leeg
                // voor. Directe dispatch naar de interface-functie (declare/
                // default-body, of een C-implementatie uit jcl-lite) is dan de
                // enige juiste route — bv. annotatie-types, waarvan de
                // instanties door de C-runtime worden gemaakt.
                return false;
            }
            slot = layout.itableSlot(call.function(), call.paramTypes());
        }
        if (slot < 0) {
            // Het statische type is een interface zonder eigen layout-slot (bv.
            // een receiver die naar een opaque pointer is gedegenereerd): val terug
            // op de globale slot-index van de interface-methode.
            slot = classLayouts.interfaceSlotIndex(call.function(), call.paramTypes());
        }
        if (slot < 0) return false;

        LLVMTypeRef ptrType = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);

        // Het itable-veld staat op dezelfde index in de header van elk object,
        // dus de concrete layout van het ontvangertype is hier niet nodig: een
        // GEP op de header zelf volstaat (en werkt ook als het ontvangertype
        // tot een pointer zonder descriptor is gedegenereerd).
        emitReceiverNullCheck(obj, call.function());
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
        List<IRType> fnParams = indirectCallParams(call);
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
    /**
     * Een virtuele of interface-aanroep op {@code null} gooit
     * NullPointerException (JLS §15.12.4.4), in plaats van de vtable/itable
     * uit adres 0 te lezen. picocli rekent daarop: {@code Ansi.isTTY()} doet
     * {@code System.class.getMethod("console").invoke(null)} binnen een
     * {@code catch (Exception)}. De worp loopt via {@link #emitCallOrInvoke},
     * zodat een omringende try hem vangt.
     */
    private void emitReceiverNullCheck(final LLVMValueRef obj, final String callee) {
        if (LLVMIsConstant(obj) != 0 && LLVMIsNull(obj) == 0) {
            return; // niet-null constante, bv. een String-literal
        }
        LLVMValueRef npe = globalValueMap.get("@nabu_throw_npe");
        final PointerPointer<Pointer> npeParams = new PointerPointer<>(1);
        npeParams.put(0, LLVMPointerTypeInContext(ctx, 0));
        final LLVMTypeRef npeType = LLVMFunctionType(LLVMVoidTypeInContext(ctx), npeParams, 1, 0);
        final LLVMBasicBlockRef current = LLVMGetInsertBlock(builder);
        final LLVMValueRef function = LLVMGetBasicBlockParent(current);
        if (npe == null || npe.isNull()) {
            final LLVMModuleRef mod = LLVMGetGlobalParent(function);
            npe = LLVMGetNamedFunction(mod, "nabu_throw_npe");
            if (npe == null || npe.isNull()) {
                npe = LLVMAddFunction(mod, "nabu_throw_npe", npeType);
                LLVMSetLinkage(npe, LLVMExternalLinkage);
            }
            globalValueMap.put("@nabu_throw_npe", npe);
        }
        // De nieuwe blokken liggen in dezelfde try-regio als het huidige: zonder
        // die handler-erfenis werd elke aanroep ná de null-check een gewone call
        // in plaats van een invoke, en ving de omringende catch niets meer
        // (picocli's execute() liet zijn eigen ParameterException ontsnappen).
        final String handler = getHandlerLabel();
        final LLVMBasicBlockRef isNullBB = LLVMAppendBasicBlockInContext(ctx, function,
                new BytePointer("receiver.null"));
        final LLVMBasicBlockRef nonNullBB = LLVMAppendBasicBlockInContext(ctx, function,
                new BytePointer("receiver.nonnull"));
        if (handler != null) {
            continuationHandlers.put(isNullBB, handler);
            continuationHandlers.put(nonNullBB, handler);
        }
        final LLVMValueRef isNull = LLVMBuildICmp(builder, LLVMIntEQ, obj,
                LLVMConstNull(LLVMTypeOf(obj)), ".receiver.isnull");
        LLVMBuildCondBr(builder, isNull, isNullBB, nonNullBB);
        LLVMPositionBuilderAtEnd(builder, isNullBB);
        // De aanroepplek als C-string: nabu_throw_npe meldt hem bij
        // NABU_TRACE_NPE (een samengevoegde null-tak verraadt hem anders niet).
        final String site = callee + " in " + enclosingFunctionName();
        final LLVMValueRef siteStr = LLVMBuildGlobalStringPtr(builder, site, ".npe.site");
        final PointerPointer<Pointer> npeArgs = new PointerPointer<>(1);
        npeArgs.put(0, siteStr);
        emitCallOrInvoke("nabu_throw_npe", npeType, npe, npeArgs, 1, null);
        LLVMBuildUnreachable(builder);
        LLVMPositionBuilderAtEnd(builder, nonNullBB);
    }

    private LLVMValueRef emitCallOrInvoke(String calleeName,
                                          LLVMTypeRef fnType,
                                          LLVMValueRef fn,
                                          PointerPointer<Pointer> args,
                                          int argCount,
                                          IRValue resultValue) {
        String name = resultValue != null ? nameOf(resultValue) : "";

        // Integer-argumenten naar de breedte van de gedeclareerde parameter
        // (bytecode geeft een int door aan een boolean/byte/char/short-param).
        if (argCount == LLVMCountParamTypes(fnType) && argCount > 0) {
            PointerPointer<LLVMTypeRef> paramTypes = new PointerPointer<>(argCount);
            LLVMGetParamTypes(fnType, paramTypes);
            for (int i = 0; i < argCount; i++) {
                LLVMValueRef arg = new LLVMValueRef(args.get(i));
                final boolean unsigned = unsignedArgs != null && unsignedArgs.length == argCount && unsignedArgs[i];
                LLVMValueRef coerced = coerceTo(arg, paramTypes.get(LLVMTypeRef.class, i), unsigned);
                if (coerced != arg) args.put(i, coerced);
            }
        }

        String handlerLabel = getHandlerLabel();
        if (handlerLabel != null) {
            LLVMBasicBlockRef currentBB = LLVMGetInsertBlock(builder);
            LLVMBasicBlockRef contBB = LLVMAppendBasicBlockInContext(ctx,
                    LLVMGetBasicBlockParent(currentBB),
                    new BytePointer("invoke.cont." + calleeName));
            LLVMBasicBlockRef handlerBB = blockMap.get(handlerLabel);

            if (handlerBB == null)
                throw new IllegalStateException("Handler block not found: " + handlerLabel);

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
        return isConstructorCall(call) && !hasExplicitReceiver(call)
                ? call.args().size() + 1 : call.args().size();
    }

    /**
     * Of een constructor-call zijn receiver al als eerste operand draagt.
     *
     * <p>De source-frontend laat 'this' weg (het object komt uit de
     * voorafgaande HeapAlloc, zie {@link #prependThis}); de bytecode-lowering
     * vertaalt {@code new/dup/invokespecial <init>} daarentegen letterlijk en
     * geeft de receiver expliciet mee, net als bij elke andere niet-statische
     * call. De operandenlijst is dan precies één langer dan het
     * parameterprofiel. Nogmaals 'this' ervoor zetten gaf
     * {@code StringBuilder_init(this, this)} — "Incorrect number of arguments".
     */
    private boolean hasExplicitReceiver(final IRInstruction.Call call) {
        return call.paramTypes() != null
                && call.args().size() == call.paramTypes().size() + 1;
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
                    "Constructor call '" + functionName
                            + "' without a preceding HeapAlloc (this is missing)"
                            + "; function=" + currentFunctionName
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
                throw new IllegalStateException("Handler block not found: " + handlerLabel);

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
            throw new IllegalStateException("Unknown branch labels: " + cb.trueLabel() + " / " + cb.falseLabel());
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
        else {
            // `ireturn` van een boolean/byte/char/short-methode levert een int.
            LLVMTypeRef returnType = LLVMGetReturnType(LLVMGlobalGetValueType(
                    LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))));
            LLVMBuildRet(builder, coerceTo(resolveValue(ret.value()), returnType, isUnsigned(ret.value())));
        }
    }

    // -------------------------------------------------------
    // Cast
    // -------------------------------------------------------

    private void emitCast(IRInstruction.Cast cast) {
        LLVMValueRef src    = resolveValue(cast.source());
        LLVMTypeRef  target = types.map(cast.targetType());
        BytePointer  name   = new BytePointer(nameOf(cast.result()));

        // De keuze volgt het WERKELIJKE LLVM-type van de bron, niet het
        // IR-type: dat laatste klopt niet altijd (een array-allocatie draagt
        // het element-type, een boolean is na een merge-slot een i32), en een
        // bitcast tussen bv. ptr en i32 of float en double is ongeldig.
        LLVMTypeRef actual = LLVMTypeOf(src);
        boolean srcInt = isIntegerType(actual);
        boolean dstInt = isIntegerType(target);
        boolean srcFp = isFloatingType(actual);
        boolean dstFp = isFloatingType(target);
        // Een char verbreedt zonder teken (JLS 5.1.2): (int) van U+FFFF is 65535.
        boolean srcUnsigned = isUnsigned(cast.source());
        LLVMValueRef result;
        if (srcInt && dstInt) {
            int from = LLVMGetIntTypeWidth(actual);
            int to = LLVMGetIntTypeWidth(target);
            if (from == to) result = src;
            else if (from > to) result = LLVMBuildTrunc(builder, src, target, name);
            else if (from == 1 || srcUnsigned) result = LLVMBuildZExt(builder, src, target, name);
            else result = LLVMBuildSExt(builder, src, target, name);
        } else if (srcInt && dstFp) {
            result = srcUnsigned
                    ? LLVMBuildUIToFP(builder, src, target, name)
                    : LLVMBuildSIToFP(builder, src, target, name);
        } else if (srcFp && dstInt) {
            result = LLVMBuildFPToSI(builder, src, target, name);
        } else if (srcFp && dstFp) {
            result = LLVMBuildFPCast(builder, src, target, name);
        } else if (isPointerType(actual) && dstInt) {
            result = LLVMBuildPtrToInt(builder, src, target, name);
        } else if (srcInt && isPointerType(target)) {
            result = intToPointer(src, target);
        } else {
            result = LLVMBuildBitCast(builder, src, target, name);
        }
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
            throw new IllegalStateException("Runtime helper @nabu_instanceof not defined");

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
            throw new IllegalStateException("Runtime helper @nabu_throw not defined");

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
                throw new IllegalStateException("Handler block not found: " + handlerLabel);
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
            // Onbekende (library) exceptionklasse: net als runHeapAllocFallback
            // kennen we geen layout, dus alloceren we zelf een header (+
            // state-slot) en laten we de runtime de type-info zetten via
            // nabu_init_object. Zonder deze aanroep bleef de header leeg
            // (type == NULL), waardoor nabu_instanceof/nabu_can_catch er
            // nooit een match op vonden en elke catch dus werd overgeslagen
            // (tot aan "onbehandelde exception").
            final long headerSize = computeSizeOf(classLayouts.objectHeaderType());
            if (thrownType instanceof IRType.Ptr p && p.jvmDescriptor() != null) {
                final String internalName = ClassLayouts.fromDescriptor(p.jvmDescriptor());
                LLVMValueRef obj = allocForStrategy(headerSize + JCL_STATE_SLOT, ".exc");
                final LLVMValueRef initObjFn = globalValueMap.get("@nabu_init_object");
                if (initObjFn == null) {
                    throw new IllegalStateException("Runtime helper @nabu_init_object not defined");
                }
                final LLVMValueRef nameStr = constants.resolveString(internalName, IRGlobal.Linkage.PRIVATE);
                PointerPointer<Pointer> args = new PointerPointer<>(2);
                args.put(0, obj);
                args.put(1, nameStr);
                return LLVMBuildCall2(builder, LLVMGlobalGetValueType(initObjFn), initObjFn, args, 2,
                        ".exc.init");
            }
            return allocForStrategy(headerSize, ".exc");
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
                    throw new IllegalStateException("Allocation helper missing");
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
                final LLVMValueRef javaString = constants.javaStringForGlobal(n.name());
                if (javaString != null) {
                    yield javaString;
                }
                LLVMValueRef v = globalValueMap.get(n.name());
                if (v == null && !n.name().startsWith("@")
                        && n.ownerType() instanceof IRType.Ptr ownerPtr
                        && ownerPtr.jvmDescriptor() != null) {
                    final var desc = ownerPtr.jvmDescriptor();
                    if (desc.startsWith("L") && desc.endsWith(";")) {
                        final var dotted = desc.substring(1, desc.length() - 1)
                                .replace('/', '.');
                        v = globalValueMap.get("@" + dotted + "_" + n.name());
                        if (v == null) {
                            v = globalValueMap.get(dotted + "_" + n.name());
                        }
                        if (v == null) {
                            // De source-frontend declareert een statisch veld
                            // onder de simple naam van zijn klasse
                            // (`ParserSpec_DEFAULT_SEPARATOR`). Exact op die
                            // naam zoeken, vóór de suffix-zoektocht hieronder:
                            // die pakt het eerste veld met dezelfde naam, van
                            // welke klasse dan ook.
                            final String simpleOwner = dotted.substring(
                                    Math.max(dotted.lastIndexOf('.'), dotted.lastIndexOf('$')) + 1);
                            v = globalValueMap.get("@" + simpleOwner + "_" + n.name());
                        }
                    }
                }
                if (v == null && n.name().startsWith("@")
                        && n.ownerType() instanceof IRType.Ptr ownerPtr
                        && ownerPtr.jvmDescriptor() != null) {
                    final var desc = ownerPtr.jvmDescriptor();
                    if (desc.startsWith("L") && desc.endsWith(";")) {
                        final var dotted = desc.substring(1, desc.length() - 1)
                                .replace('/', '.');
                        v = globalValueMap.get("@" + dotted + "_" + n.name().substring(1));
                        if (v == null) {
                            v = globalValueMap.get(dotted + "_" + n.name().substring(1));
                        }
                    }
                }
                if (v == null) {
                    // Geërfd statisch veld: bytecode mag de SUBKLASSE als
                    // eigenaar noemen (bv. `Field.reflectionFactory`, dat in
                    // AccessibleObject staat). Loop de super-keten af vóór de
                    // suffix-fallback hieronder, die anders een willekeurige
                    // gelijknamige global zou kunnen kiezen.
                    final String plain = n.name().startsWith("@")
                            ? n.name().substring(1) : n.name();
                    String inherited = classLayouts.superCallTarget(plain);
                    while (v == null && inherited != null) {
                        v = globalValueMap.get("@" + inherited);
                        inherited = classLayouts.superCallTarget(inherited);
                    }
                }
                if (v == null) {
                    // A fully qualified name (`java.lang.System_out`), also
                    // the key declareOutsideStaticField registers.
                    v = globalValueMap.get("@" + n.name());
                }
                if (v == null) {
                    v = suffixMatches.get(n.name());
                }
                if (v == null) {
                    // Legacy owner-in-name-codeing (wat het ASM-backend via
                    // resolveOwnerForNamed oplost): een `Named` zonder
                    // owner-fundamental. Zoek de global waarvan de key op
                    // `<owner>_<name>` (of `<name>` zelf) eindigt. A linear
                    // scan of every global, so the hit is remembered.
                    for (var entry : globalValueMap.entrySet()) {
                        if (entry.getKey().endsWith("$" + n.name())
                                || entry.getKey().endsWith("." + n.name())
                                || entry.getKey().endsWith("_" + n.name())) {
                            v = entry.getValue();
                            suffixMatches.put(n.name(), v);
                            break;
                        }
                    }
                }
                if (v == null) {
                    v = declareOutsideStaticField(n);
                }
                if (v == null)
                    throw new IllegalStateException("Unknown global: " + n.name());
                yield preferGlobalVariable(n, v);
            }
            case IRValue.FunctionRef r -> {
                LLVMValueRef v = globalValueMap.get("@" + r.name());
                if (v == null)
                    throw new IllegalStateException("Unknown function reference: " + r.name());
                yield v;
            }
            case IRValue.ConstString c ->
                // Inline string — maak global aan via ConstantResolver
                constants.resolveJavaString(c.value());

            case IRValue.ConstClass c -> emitClassLiteral(c);

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

    /**
     * Een class-literal ({@code X.class}) wordt het Class-object dat de runtime
     * per naam interneert ({@code nabu_class_literal}). De kale C-string met de
     * naam is geen object: een virtuele aanroep erop (bv. {@code equals} via een
     * Object-parameter) las de tekst als vtable-pointer. Phi's zijn op dit punt
     * al weg (PhiElimination), dus de aanroep staat altijd in een gewoon blok.
     */
    private LLVMValueRef emitClassLiteral(final IRValue.ConstClass c) {
        final LLVMValueRef fn = globalValueMap.get("@nabu_class_literal");
        final LLVMValueRef name = constants.resolveString(
                ConstantResolver.classNameFromDescriptor(c.type()), IRGlobal.Linkage.PRIVATE);
        // Een nabu_*-runtime-helper verwacht de naam zelf (const char*).
        if (fn == null || constants.isRawStrings()) {
            return name;
        }
        final PointerPointer<Pointer> args = new PointerPointer<>(1);
        args.put(0, name);
        final LLVMValueRef cls = LLVMBuildCall2(builder, LLVMGlobalGetValueType(fn), fn,
                args, 1, ".class");
        Reference.reachabilityFence(args);
        return cls;
    }

    /**
     * Een statisch veld en een methode van dezelfde klasse kunnen dezelfde
     * IR-naam dragen (bv. het veld {@code BuiltinClassLoader.packageToModule}
     * naast de methode {@code packageToModule()}). De functie-declaratie
     * overschrijft dan de {@code "@"+naam}-sleutel van het veld in
     * globalValueMap, waardoor een veld-load/-store op de FUNCTIE zou werken.
     * Globals worden vóór de functies geëmitteerd, dus de global variable
     * draagt in de module de kale naam (de functie krijgt LLVM's volgnummer);
     * die heeft hier voorrang.
     */
    private LLVMValueRef preferGlobalVariable(IRValue.Named named, LLVMValueRef resolved) {
        final LLVMValueRef global = globalVariableFor(named, resolved);
        final LLVMValueRef asGlobalVar = LLVMIsAGlobalVariable(global);
        if (asGlobalVar != null && !asGlobalVar.isNull()) {
            // Toegang tot een statisch veld initialiseert de klasse (JLS §12.4.1).
            final String plain = named.name().startsWith("@")
                    ? named.name().substring(1) : named.name();
            if (!plain.startsWith(".")) {
                // Een source-veld-toegang noemt alleen de veldnaam (`ENGLISH`)
                // met het eigenaartype apart; uit de naam alleen viel geen
                // eigenaar af te leiden en bleef bv. Locale ongeïnitialiseerd.
                String owner = named.ownerType() instanceof IRType.Ptr ownerPtr
                        && ownerPtr.jvmDescriptor() != null
                        && ownerPtr.jvmDescriptor().startsWith("L")
                        ? ClassLayouts.fromDescriptor(ownerPtr.jvmDescriptor())
                        : null;
                if (owner == null || classLayouts.moduleOf(owner) == null) {
                    owner = classLayouts.ownerInternalName(plain);
                }
                emitClassInit(owner);
            }
        }
        return global;
    }

    /**
     * Een statisch veld van een JDK-klasse buiten de sluiting (bv.
     * {@code File.separatorChar}, nu {@code java.io.File} door de C-runtime
     * wordt geleverd): een nul-geïnitialiseerde global, zodat de module
     * compileert en linkt. De waarde is dus de default (0/null) tot de
     * runtime het veld zelf gaat leveren; de waarschuwing noemt het veld.
     */
    private LLVMValueRef declareOutsideStaticField(final IRValue.Named named) {
        final String plain = named.name().startsWith("@")
                ? named.name().substring(1) : named.name();
        if (plain.indexOf('.') < 0 || plain.startsWith(".") || named.type() == null) {
            return null;
        }
        // Alleen voor klassen buiten deze compilatie: een veld van een klasse
        // die wél gecompileerd wordt en hier niet gevonden is, is een
        // lookup-fout en mag niet stil een tweede, lege global krijgen.
        if (classLayouts.ownerInternalName(plain) != null) {
            return null;
        }
        final LLVMBasicBlockRef bb = LLVMGetInsertBlock(builder);
        if (bb == null || bb.isNull()) return null;
        final LLVMModuleRef mod = LLVMGetGlobalParent(LLVMGetBasicBlockParent(bb));
        final LLVMTypeRef type = types.map(named.type());
        if (LLVMGetTypeKind(type) == LLVMVoidTypeKind
                || LLVMGetTypeKind(type) == LLVMFunctionTypeKind) {
            return null;
        }
        final LLVMValueRef global = LLVMAddGlobal(mod, type, new BytePointer(plain));
        LLVMSetInitializer(global, LLVMConstNull(type));
        globalValueMap.put("@" + plain, global);
        System.err.println("[nabu/LLVM] warning: static field " + plain
                + " belongs to a class outside the compiled closure; it has"
                + " the default value (0/null).");
        return global;
    }

    private LLVMValueRef globalVariableFor(IRValue.Named named, LLVMValueRef resolved) {
        LLVMValueRef asGlobalVar = LLVMIsAGlobalVariable(resolved);
        if (asGlobalVar != null && !asGlobalVar.isNull()) return resolved;

        LLVMBasicBlockRef bb = LLVMGetInsertBlock(builder);
        if (bb == null || bb.isNull()) return resolved;
        LLVMModuleRef mod = LLVMGetGlobalParent(LLVMGetBasicBlockParent(bb));
        String plain = named.name().startsWith("@")
                ? named.name().substring(1) : named.name();
        LLVMValueRef global = LLVMGetNamedGlobal(mod, new BytePointer(plain));
        return global != null && !global.isNull() ? global : resolved;
    }

    // -------------------------------------------------------
    // Klasse-initialisatie
    // -------------------------------------------------------

    /** Interne klassenaam -> de eenmalige initialisatie-functie, of null als er niets te doen is. */
    private final Map<String, LLVMValueRef> classInitFns = new HashMap<>();

    /**
     * Roept (hooguit eenmaal effectief) de statische initializer aan van een
     * uit bytecode gecompileerde klasse, met zijn super-klassen eerst.
     *
     * <p>De JVM initialiseert een klasse lazy bij het eerste gebruik. Een
     * {@code _clinit} uit bytecode wordt daarom niet vooraf gedraaid maar via
     * deze bewaking: bij toegang tot een statisch veld en aan het begin van
     * elke statische methode en constructor.
     */
    public void emitClassInit(final String internalName) {
        if (internalName == null) return;
        final LLVMBasicBlockRef bb = LLVMGetInsertBlock(builder);
        if (bb == null || bb.isNull()) return;
        final LLVMValueRef initFn = classInitFn(internalName,
                LLVMGetGlobalParent(LLVMGetBasicBlockParent(bb)));
        if (initFn == null) return;
        LLVMBuildCall2(builder, LLVMGlobalGetValueType(initFn), initFn,
                new PointerPointer<>(0), 0, "");
    }

    /**
     * De (zo nodig nu aangemaakte) {@code <klasse>.clinit.once}-functie, of
     * null als de klasse en haar supers geen {@code <clinit>} hebben. Voor de
     * runtime-metadata die de initialisatie van buitenaf moet kunnen starten.
     */
    LLVMValueRef classInitFunction(final String internalName, final LLVMModuleRef mod) {
        return classInitFn(internalName, mod);
    }

    private LLVMValueRef classInitFn(final String internalName, final LLVMModuleRef mod) {
        if (classInitFns.containsKey(internalName)) {
            return classInitFns.get(internalName);
        }
        classInitFns.put(internalName, null);   // breekt een cyclische super-keten

        final String dotted = internalName.replace('/', '.');
        LLVMValueRef clinit = globalValueMap.get("@" + dotted + "_clinit");
        if (clinit == null || clinit.isNull()) {
            clinit = globalValueMap.get("@" + dotted + "_clinit#");
        }
        if (clinit != null && !clinit.isNull()) {
            final LLVMValueRef asFn = LLVMIsAFunction(clinit);
            if (asFn == null || asFn.isNull() || LLVMCountParams(clinit) != 0) clinit = null;
        } else {
            clinit = null;
        }
        if (clinit == null) {
            // Een source-klasse noemt haar initializer `Owner_<clinit>` (korte
            // eigenaar, dus niet uniek over klassen): via haar eigen module.
            final var module = classLayouts.moduleOf(internalName);
            if (module != null) {
                for (final var fn : module.functions()) {
                    if (fn.name != null && fn.name.contains("<clinit>") && fn.params.isEmpty()
                            && !fn.blocks().isEmpty()) {
                        final LLVMValueRef value = classLayouts.functionValue(fn);
                        if (value != null && !value.isNull()) clinit = value;
                        break;
                    }
                }
            }
        }
        final String superName = classLayouts.superInternalName(internalName);
        final LLVMValueRef superInit = superName != null ? classInitFn(superName, mod) : null;
        if (clinit == null && superInit == null) {
            return null;
        }

        final LLVMTypeRef i1 = LLVMInt1TypeInContext(ctx);
        final LLVMTypeRef fnType = LLVMFunctionType(LLVMVoidTypeInContext(ctx),
                new PointerPointer<>(0), 0, 0);
        final LLVMValueRef fn = LLVMAddFunction(mod, dotted + ".clinit.once", fnType);
        LLVMSetLinkage(fn, LLVMInternalLinkage);
        final LLVMValueRef flag = LLVMAddGlobal(mod, i1,
                new BytePointer(dotted + ".clinit.done"));
        LLVMSetLinkage(flag, LLVMInternalLinkage);
        LLVMSetInitializer(flag, LLVMConstInt(i1, 0, 0));

        final LLVMBuilderRef b = LLVMCreateBuilderInContext(ctx);
        final LLVMBasicBlockRef entry = LLVMAppendBasicBlockInContext(ctx, fn, new BytePointer("entry"));
        final LLVMBasicBlockRef run = LLVMAppendBasicBlockInContext(ctx, fn, new BytePointer("run"));
        final LLVMBasicBlockRef done = LLVMAppendBasicBlockInContext(ctx, fn, new BytePointer("done"));
        LLVMPositionBuilderAtEnd(b, entry);
        LLVMBuildCondBr(b, LLVMBuildLoad2(b, i1, flag, new BytePointer("already")), done, run);
        LLVMPositionBuilderAtEnd(b, run);
        // Vlag vóór de initializer zetten: de initializer raakt zijn eigen
        // statische velden aan en zou anders zichzelf opnieuw aanroepen.
        LLVMBuildStore(b, LLVMConstInt(i1, 1, 0), flag);
        if (superInit != null) {
            LLVMBuildCall2(b, fnType, superInit, new PointerPointer<>(0), 0, "");
        }
        if (clinit != null) {
            LLVMBuildCall2(b, LLVMGlobalGetValueType(clinit), clinit, new PointerPointer<>(0), 0, "");
        }
        LLVMBuildBr(b, done);
        LLVMPositionBuilderAtEnd(b, done);
        LLVMBuildRetVoid(b);
        LLVMDisposeBuilder(b);

        classInitFns.put(internalName, fn);
        return fn;
    }

    // -------------------------------------------------------
    // Hulp
    // -------------------------------------------------------

    /**
     * Per argument van de laatst gebouwde argumentlijst: of het een char is.
     * emitCallOrInvoke past de argumenten daarna pas aan de parametertypes
     * aan en ziet zelf alleen LLVM-waarden.
     */
    private boolean[] unsignedArgs;

    private PointerPointer<Pointer> buildArgs(java.util.List<IRValue> args) {
        unsignedArgs = new boolean[args.size()];
        for (int i = 0; i < args.size(); i++) unsignedArgs[i] = isUnsigned(args.get(i));
        LLVMValueRef[] arr = args.stream()
                .map(this::resolveValue)
                .toArray(LLVMValueRef[]::new);
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == null || arr[i].isNull()) {
                throw new IllegalStateException(
                        "arg " + i + " null for call with args=" + args);
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
        // Binnen de eigenaar-module is de veldnaam eenduidig en dus leidend;
        // de teller van de frontend is alleen een hint. Een teller die naast
        // de synthetische velden (this$0) telt, wees anders zonder foutmelding
        // naar een ANDER, bestaand veld.
        final int ownerByName = directOwner != null
                ? classLayouts.fieldIndexIn(classLayouts.moduleOf(directOwner.internalName), named.name())
                : -1;
        if (ownerByName >= 0) {
            layout = directOwner;
            fieldIndex = ownerByName;
        } else if (directOwner != null && fieldIndex >= 0) {
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
            ClassLayouts.ClassLayout inherited = null;
            int inheritedIndex = -1;
            if (byName < 0 && directOwner != null) {
                // Het veld is geërfd: de instructie noemt de subklasse als
                // eigenaar. De super-velden staan in de subklasse-struct op
                // dezelfde indices, dus de layout van de declarerende
                // super-klasse geeft het juiste adres.
                for (String sup = classLayouts.superInternalName(directOwner.internalName);
                     sup != null && inherited == null;
                     sup = classLayouts.superInternalName(sup)) {
                    final int idx = classLayouts.fieldIndexIn(classLayouts.moduleOf(sup), named.name());
                    if (idx >= 0) {
                        final var supLayout = classLayouts.getFor(new IRType.Ptr(IRType.I8, "L" + sup + ";"));
                        if (supLayout != null && supLayout.structIndex(idx) >= 0) {
                            inherited = supLayout;
                            inheritedIndex = idx;
                        }
                    }
                }
            }
            if (byName >= 0) {
                    fieldIndex = byName;
            } else if (inherited != null) {
                layout = inherited;
                fieldIndex = inheritedIndex;
            } else {
                final var fieldOwner = classLayouts.findField(named.name(), fieldIndex);
                if (fieldOwner == null) {
                    // Bibliotheekklasse buiten deze compilatie: we kennen geen
                    // layout en dus geen veld-offset. Dat is een beperking van
                    // de backend, geen fatale compilatiefout.
                    warnUnsupportedField(named, "no layout for the owner");
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
            warnUnsupportedField(named, "unknown field offset in " + layout.internalName
                    + " (fields: [" + classLayouts.fieldDiagnostics(layout.internalName) + "])");
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
        System.err.println("[nabu/LLVM] warning: field '" + named.name()
                + "' (index " + named.fieldIndex() + ") is not accessible — " + reason
                + ". The field yields an undefined value; add the class to the"
                + " sources (or avoid the field) for real field access.");
    }
}


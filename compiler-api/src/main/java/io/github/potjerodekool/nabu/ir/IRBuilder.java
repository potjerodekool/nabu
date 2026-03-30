package io.github.potjerodekool.nabu.ir;

import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.tools.TodoException;

import java.util.*;

public class IRBuilder {

    private final IRModule module;

    private IRFunction   currentFunction;
    private IRBasicBlock currentBlock;
    private int          tempCounter    = 0;
    private int          stringCounter  = 0;
    private int          labelCounter   = 0;

    private SourceLocation currentLocation = SourceLocation.UNKNOWN;

    // Scoped symboltabel: naam → IRValue (pointer na Alloca, of global ptr)
    private final Deque<Map<String, IRValue>> scopes = new ArrayDeque<>();

    private final Map<String, Integer> labelCounters = new HashMap<>();

    public IRBuilder(String moduleName) {
        this.module = new IRModule(moduleName);
    }

    // -------------------------------------------------------
    // Locatie
    // -------------------------------------------------------

    public void setLocation(SourceLocation loc) {
        this.currentLocation = loc != null ? loc : SourceLocation.UNKNOWN;
    }

    public void setLocation(String file, int line, int col) {
        this.currentLocation = new SourceLocation(file, line, col);
    }

    public SourceLocation currentLocation() { return currentLocation; }

    // -------------------------------------------------------
    // Module
    // -------------------------------------------------------

    public IRModule build() { return module; }

    // -------------------------------------------------------
    // Functies
    // -------------------------------------------------------

    public IRValue declareExternalFunction(String name, IRType.Function fnType) {
        // Voeg een lege IRFunction toe als externe declaratie
        var fn = new IRFunction(name, fnType.returnType(),
                buildParamList(fnType),
                SourceLocation.UNKNOWN,
                false);
        fn.markExternal();          // geen body verwacht
        module.addFunction(fn);
        return new IRValue.FunctionRef(name, fnType);
    }

    private List<IRValue> buildParamList(IRType.Function fnType) {
        List<IRValue> params = new ArrayList<>();
        for (int i = 0; i < fnType.paramTypes().size(); i++)
            params.add(new IRValue.Temp("%arg" + i, fnType.paramTypes().get(i)));
        return params;
    }

    public IRFunction beginFunction(String name, IRType returnType,
                                    List<IRValue> params,
                                    final boolean isStatic) {
        currentFunction = new IRFunction(name, returnType, params, currentLocation, isStatic);
        module.addFunction(currentFunction);
        tempCounter = 0;
        currentBlock = beginBlock("entry");
        pushScope();
        for (IRValue p : params) {
            final var paramName = IRValue.nameOf(p);
            String pname = IRValue.nameOf(p).startsWith("%") ? paramName.substring(1) : paramName;
            define(pname, p);
        }
        return currentFunction;
    }

    public void endFunction() {
        if (!currentBlock.isTerminated()) {
            if (currentFunction.returnType == IRType.VOID) {
                emitReturn(null);
            } else {
                throw new IllegalStateException(
                    "Functie '" + currentFunction.name + "' mist een return-instructie");
            }
        }
        popScope();
        currentFunction = null;
    }

    // -------------------------------------------------------
    // Blokken
    // -------------------------------------------------------

    public IRBasicBlock beginBlock(String label) {
        var block = new IRBasicBlock(uniqueLabel(label));
        if (currentFunction != null) currentFunction.addBlock(block);
        currentBlock = block;
        return block;
    }

    public void setCurrentBlock(IRBasicBlock block) {
        currentBlock = block;
    }

    public boolean currentBlockTerminated() {
        return currentBlock != null && currentBlock.isTerminated();
    }

    // -------------------------------------------------------
    // Instructies
    // -------------------------------------------------------

    public IRValue emitBinaryOp(IRInstruction.BinaryOp.Op op, IRValue left, IRValue right) {
        var result = fresh(left.type());
        emit(new IRInstruction.BinaryOp(result, op, left, right, currentLocation));
        return result;
    }

    public IRValue emitAlloca(String name, IRType type) {
        var ptr = new IRValue.Temp("%" + name + ".ptr", new IRType.Ptr(type));
        emit(new IRInstruction.Alloca(ptr, type, currentLocation));
        define(name, ptr);
        return ptr;
    }

    public IRValue emitLoad(IRValue ptr) {
        final IRType pointee;

        if (ptr.type() instanceof IRType.Ptr ptrType) {
            pointee = ptrType.pointee();
        } else {
            pointee = ptr.type();
        }

        var result = fresh(pointee);
        emit(new IRInstruction.Load(result, ptr, currentLocation));
        return result;
    }

    public void emitStore(IRValue ptr, IRValue value) {
        emit(new IRInstruction.Store(ptr, value, currentLocation));
    }

    public IRValue emitCall(String fnName, IRType returnType, List<IRValue> args) {
        IRValue result = returnType == IRType.VOID ? null : fresh(returnType);
        emit(new IRInstruction.Call(result, fnName, args, currentLocation));
        return result;
    }

    public IRValue emitIndirectCall(IRValue callee, IRType.Function fnType,
                                    List<IRValue> args) {
        IRValue result = fnType.returnType() == IRType.VOID ? null : fresh(fnType.returnType());
        emit(new IRInstruction.IndirectCall(result, callee, fnType, args, currentLocation));
        return result;
    }

    public void emitBranch(IRBasicBlock target) {
        if (!currentBlock.isTerminated())
            emit(new IRInstruction.Branch(target.label(), currentLocation));
    }

    public void emitCondBranch(IRValue cond, IRBasicBlock ifTrue, IRBasicBlock ifFalse) {
        emit(new IRInstruction.CondBranch(cond, ifTrue.label(), ifFalse.label(), currentLocation));
    }

    public void emitReturn(IRValue value) {
        emit(new IRInstruction.Return(value, currentLocation));
    }

    public IRValue emitCast(IRValue source, IRType target) {
        var result = fresh(target);
        emit(new IRInstruction.Cast(result, source, target, currentLocation));
        return result;
    }

    // -------------------------------------------------------
    // Globals
    // -------------------------------------------------------

    public IRValue declareGlobal(String name, IRType type, IRValue initializer) {
        module.addGlobal(IRGlobal.mutable(name, type, initializer));
        return new IRValue.Named("@" + name, new IRType.Ptr(type));
    }

    public IRValue declareConstant(String name, IRType type, IRValue value) {
        module.addGlobal(IRGlobal.constant(name, type, value));
        return new IRValue.Named("@" + name, new IRType.Ptr(type));
    }

    public IRValue declareExternalGlobal(String name, IRType type) {
        module.addGlobal(IRGlobal.external(name, type));
        return new IRValue.Named("@" + name, new IRType.Ptr(type));
    }

    // -------------------------------------------------------
    // Constanten
    // -------------------------------------------------------

    public IRValue constInt(long value)              { return IRValue.ofI32(value); }
    public IRValue constInt(long value, int bits)    { return IRValue.ofInt(value, bits); }
    public IRValue constFloat(double value)          { return IRValue.ofFloat(value); }
    public IRValue constBool(boolean value)          { return IRValue.ofBool(value); }

    public IRValue constString(String value) {
        String globalName = ".str." + stringCounter++;
        module.addGlobal(IRGlobal.stringLiteral(globalName, value));
        return new IRValue.Named("@" + globalName, new IRType.Ptr(IRType.I8));
    }

    // -------------------------------------------------------
    // Functiereferenties
    // -------------------------------------------------------

    public IRValue functionRef(String name, IRType.Function fnType) {
        return new IRValue.FunctionRef(name, fnType);
    }

    public IRValue storeFunctionPointer(String varName, IRValue funcRef) {
        IRValue slot = emitAlloca(varName, funcRef.type());
        emitStore(slot, funcRef);
        return slot;
    }

    // -------------------------------------------------------
    // Symboltabel
    // -------------------------------------------------------

    public void pushScope() { scopes.push(new LinkedHashMap<>()); }

    public void popScope() {
        if (scopes.isEmpty())
            throw new IllegalStateException("Geen scope om te sluiten");
        scopes.pop();
    }

    public void define(String name, IRValue ptr) {
        if (scopes.isEmpty())
            throw new IllegalStateException("Geen actieve scope");
        scopes.peek().put(name, ptr);
    }

    public IRValue lookup(String name) {
        for (var scope : scopes) {
            if (scope.containsKey(name)) return scope.get(name);
        }
        // Zoek in globals
        IRGlobal global = module.globals().get(name);
        if (global != null)
            return new IRValue.Named("@" + name, global.ptrType());

        throw new IllegalStateException("Onbekende variabele: '" + name + "'");
    }

    // -------------------------------------------------------
    // Hulpmethoden
    // -------------------------------------------------------

    private void emit(IRInstruction instr) {
        if (currentBlock == null)
            throw new IllegalStateException("Geen actief basisblok");
        currentBlock.add(instr);
    }

    private IRValue.Temp fresh(IRType type) {
        return new IRValue.Temp("%" + tempCounter++, type);
    }

    private String uniqueLabel(String base) {
        int n = labelCounters.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + "." + n;
    }

    // -------------------------------------------------------
    // Standaard-initialisatoren
    // -------------------------------------------------------

    public static IRValue defaultInitializer(IRType type) {
        return switch (type) {
            case IRType.Int   t -> IRValue.ofInt(0, t.bits());
            case IRType.Float t -> IRValue.ofFloat(0.0);
            case IRType.Bool  t -> IRValue.ofBool(false);
            case IRType.Ptr   t -> IRValue.nullPtr(t.pointee());
            default             -> IRValue.undef(type);
        };
    }

    public IRBasicBlock currentBlock() {
        return currentBlock;
    }

    public IRValue emitHeapAlloc(final String newObj, final IRType objectType) {
        throw new TodoException();
    }

    public IRValue emitGEP(final IRValue array, final IRType pointee, final IRValue index) {
        throw new TodoException();
    }
}

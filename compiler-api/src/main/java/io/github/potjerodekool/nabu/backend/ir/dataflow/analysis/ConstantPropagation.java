package io.github.potjerodekool.nabu.backend.ir.dataflow.analysis;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowAnalysis;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowDirection;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Constant Propagation (dichte analyse): propageert constante waarden.
 * Lattice per variabele: UNDEFINED -> CONST(value) -> OVERDEFINED.
 */
public class ConstantPropagation implements DataFlowAnalysis<Map<String, Object>> {

    public static final Object UNDEFINED = new Object() { public String toString(){return "UNDEF";} };
    public static final Object OVERDEFINED = new Object() { public String toString(){return "OVER";} };

    @Override
    public DataFlowDirection direction() { return DataFlowDirection.FORWARD; }

    @Override
    public Map<String, Object> boundaryValue(IRFunction function) { return new LinkedHashMap<>(); }

    @Override
    public Map<String, Object> initialValue() { return new LinkedHashMap<>(); }

    @Override
    public Map<String, Object> transfer(IRBasicBlock block, Map<String, Object> in) {
        final var out = new LinkedHashMap<>(in);
        for (final var instr : block.instructions()) {
            final var res = instr.result();
            String name = (res == null) ? null : valueName(res);
            switch (instr) {
                case IRInstruction.Move m -> {
                    final Object v = evalValue(m.value(), out);
                    if (name != null) out.put(name, v);
                }
                case IRInstruction.BinaryOp op -> {
                    final Object lv = evalValue(op.left(), out);
                    final Object rv = evalValue(op.right(), out);
                    Object result;
                    if (lv instanceof IRValue && rv instanceof IRValue) {
                        // both constants
                        result = tryFold(op, (IRValue) lv, (IRValue) rv, op.result());
                    } else if (lv == UNDEFINED || rv == UNDEFINED) {
                        result = UNDEFINED;
                    } else {
                        result = OVERDEFINED;
                    }
                    if (name != null) out.put(name, result);
                }
                case IRInstruction.Phi phi -> {
                    // meet of incomings
                    Object meet = UNDEFINED;
                    for (final var inc : phi.incomingValues()) {
                        final Object v = evalValue(inc.value(), out);
                        meet = combineScalar(meet, v);
                    }
                    if (name != null) out.put(name, meet);
                }
                default -> {
                    if (name != null && !out.containsKey(name)) {
                        // unknown def -> overdefined
                        out.put(name, OVERDEFINED);
                    } else if (name != null) {
                        // redefinition of existing -> overdefined unless const propagated? conservative
                        // keep as overdefined for non-const instructions
                        if (!(instr instanceof IRInstruction.Cast)) {
                            out.put(name, OVERDEFINED);
                        }
                    }
                }
            }
        }
        return out;
    }

    private Object evalValue(IRValue v, Map<String, Object> env) {
        if (v instanceof IRValue.Temp t) {
            return env.getOrDefault(t.name(), UNDEFINED);
        }
        if (v instanceof IRValue.Named n) {
            return env.getOrDefault(n.name(), UNDEFINED);
        }
        // constants
        if (v instanceof IRValue.ConstInt || v instanceof IRValue.ConstBool ||
            v instanceof IRValue.ConstFloat || v instanceof IRValue.ConstString) {
            return v;
        }
        return OVERDEFINED;
    }

    private Object tryFold(IRInstruction.BinaryOp op, IRValue l, IRValue r, IRValue result) {
        // only fold if both constants
        if (l instanceof IRValue.ConstInt li && r instanceof IRValue.ConstInt ri) {
            // reuse ConstantFolder logic simplified
            long res = switch (op.op()) {
                case ADD -> li.value() + ri.value();
                case SUB -> li.value() - ri.value();
                case MUL -> li.value() * ri.value();
                default -> li.value();
            };
            // only claim constant for a few ops; else overdefined
            if (op.op() == IRInstruction.BinaryOp.Op.ADD ||
                op.op() == IRInstruction.BinaryOp.Op.SUB ||
                op.op() == IRInstruction.BinaryOp.Op.MUL ||
                op.op() == IRInstruction.BinaryOp.Op.DIV ||
                op.op() == IRInstruction.BinaryOp.Op.MOD) {
                return new IRValue.ConstInt(res, li.type());
            }
        }
        if (l instanceof IRValue.ConstBool lb && r instanceof IRValue.ConstBool rb) {
            if (op.op() == IRInstruction.BinaryOp.Op.AND) return new IRValue.ConstBool(lb.value() && rb.value());
            if (op.op() == IRInstruction.BinaryOp.Op.OR) return new IRValue.ConstBool(lb.value() || rb.value());
            if (op.op() == IRInstruction.BinaryOp.Op.EQ) return new IRValue.ConstBool(lb.value() == rb.value());
            if (op.op() == IRInstruction.BinaryOp.Op.NEQ) return new IRValue.ConstBool(lb.value() != rb.value());
        }
        return OVERDEFINED;
    }

    private Object combineScalar(Object a, Object b) {
        if (a == UNDEFINED) return b;
        if (b == UNDEFINED) return a;
        if (a == OVERDEFINED || b == OVERDEFINED) return OVERDEFINED;
        if (Objects.equals(a, b)) return a;
        return OVERDEFINED;
    }

    @Override
    public Map<String, Object> meet(Map<String, Object> a, Map<String, Object> b) {
        final var r = new LinkedHashMap<>(a);
        // union keys
        final var keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (final var k : keys) {
            final Object va = a.getOrDefault(k, UNDEFINED);
            final Object vb = b.getOrDefault(k, UNDEFINED);
            r.put(k, combineScalar(va, vb));
        }
        return r;
    }

    @Override
    public boolean equalsValue(Map<String, Object> a, Map<String, Object> b) {
        return Objects.equals(a, b);
    }

    private String valueName(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }
}
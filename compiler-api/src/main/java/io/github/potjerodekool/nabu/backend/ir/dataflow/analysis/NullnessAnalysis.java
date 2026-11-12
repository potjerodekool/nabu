package io.github.potjerodekool.nabu.backend.ir.dataflow.analysis;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowAnalysis;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowDirection;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Nullness-analyse (voorwaarts): bepaalt per referentie-waarde of deze
 * null, non-null of unknown is. Phi is non-null iff alle incoming non-null.
 */
public class NullnessAnalysis implements DataFlowAnalysis<Map<String, Nullness>> {

    private static final java.util.Map<String, Nullness> RETURN_NULLNESS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void setReturnNullness(String functionName, Nullness n) {
        RETURN_NULLNESS.put(functionName, n);
    }

    public static Nullness getReturnNullness(String functionName) {
        return RETURN_NULLNESS.get(functionName);
    }

    public static java.util.Map<String, Nullness> allReturnNullness() {
        return java.util.Collections.unmodifiableMap(RETURN_NULLNESS);
    }

    @Override
    public DataFlowDirection direction() { return DataFlowDirection.FORWARD; }

    @Override
    public Map<String, Nullness> boundaryValue(IRFunction function) { return new LinkedHashMap<>(); }

    @Override
    public Map<String, Nullness> initialValue() { return new LinkedHashMap<>(); }

    @Override
    public Map<String, Nullness> transfer(IRBasicBlock block, Map<String, Nullness> in) {
        final var out = new LinkedHashMap<>(in);
        for (final var instr : block.instructions()) {
            final var res = instr.result();
            final String name = (res == null) ? null : valueName(res);
            if (name == null && res == null) {
                // void instr, no tracking
            }
            Nullness n = null;
            switch (instr) {
                case IRInstruction.HeapAlloc ignored -> n = Nullness.NON_NULL;
                case IRInstruction.StackAlloc ignored -> n = Nullness.NON_NULL;
                case IRInstruction.Alloca ignored -> n = Nullness.NON_NULL; // alloca of ref -> non-null (slot addr)
                case IRInstruction.AllocaArray ignored -> n = Nullness.NON_NULL;
                case IRInstruction.Move m -> n = in.getOrDefault(valueName(m.value()), defaultNullness(m.value()));
                case IRInstruction.Cast c -> n = in.getOrDefault(valueName(c.source()), defaultNullness(c.source()));
                case IRInstruction.Phi phi -> {
                    Nullness meet = Nullness.NON_NULL;
                    boolean any = false;
                    for (final var inc : phi.incomingValues()) {
                        Nullness inv;
                        final String inName = valueName(inc.value());
                        if (inName == null) {
                            // constant
                            inv = (inc.value() instanceof IRValue.ConstNull) ? Nullness.NULL : Nullness.NON_NULL;
                        } else {
                            inv = in.getOrDefault(inName, Nullness.UNKNOWN);
                        }
                        if (!any) { meet = inv; any = true; }
                        else meet = join(meet, inv);
                    }
                    if (!any) meet = Nullness.UNKNOWN;
                    n = meet;
                }
                default -> {
                    if (res != null && res.type() instanceof IRType.Ptr) {
                        if (instr instanceof IRInstruction.Call c) {
                            final String fn = c.function();
                            final Nullness rn = (fn == null) ? null : getReturnNullness(fn);
                            if (rn != null) {
                                n = rn;
                            } else if (fn != null && (fn.contains("<init>") || fn.contains("alloc") ||
                                    fn.contains("Alloc") || fn.toLowerCase().contains("new"))) {
                                n = Nullness.NON_NULL;
                            } else {
                                n = Nullness.UNKNOWN;
                            }
                        } else {
                            n = Nullness.UNKNOWN;
                        }
                    }
                }
            }
            if (name != null && n != null) {
                out.put(name, n);
            }
        }
        return out;
    }

    private Nullness defaultNullness(IRValue v) {
        if (v instanceof IRValue.ConstNull) return Nullness.NULL;
        if (v instanceof IRValue.ConstString || v instanceof IRValue.ConstClass) return Nullness.NON_NULL;
        if (v instanceof IRValue.Temp || v instanceof IRValue.Named) return Nullness.UNKNOWN;
        return Nullness.NON_NULL;
    }

    private Nullness join(Nullness a, Nullness b) {
        if (a == b) return a;
        if (a == Nullness.UNKNOWN || b == Nullness.UNKNOWN) return Nullness.UNKNOWN;
        // NULL + NON_NULL -> UNKNOWN
        return Nullness.UNKNOWN;
    }

    @Override
    public Map<String, Nullness> meet(Map<String, Nullness> a, Map<String, Nullness> b) {
        final var r = new LinkedHashMap<>(a);
        final var keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (final var k : keys) {
            final Nullness va = a.getOrDefault(k, Nullness.UNKNOWN);
            final Nullness vb = b.getOrDefault(k, Nullness.UNKNOWN);
            // join (may): if either unknown -> unknown; null+nonnull -> unknown
            r.put(k, join(va, vb));
        }
        return r;
    }

    @Override
    public boolean equalsValue(Map<String, Nullness> a, Map<String, Nullness> b) {
        return Objects.equals(a, b);
    }

    @Override
    public Map<String, Nullness> refine(Map<String, Nullness> v, IRBasicBlock predecessorBlock,
                                       IRValue condition, boolean truthValue) {
        // Zoek de BinaryOp die `condition` definieert in het predecessor-blok.
        IRInstruction.BinaryOp cmp = null;
        if (condition instanceof IRValue.Temp ct || condition instanceof IRValue.Named cn) {
            final String cname = (condition instanceof IRValue.Temp t) ? t.name() : ((IRValue.Named) condition).name();
            for (final var instr : predecessorBlock.instructions()) {
                if (instr instanceof IRInstruction.BinaryOp b && instr.result() != null) {
                    final String rname = (instr.result() instanceof IRValue.Temp t) ? t.name()
                            : (instr.result() instanceof IRValue.Named n) ? n.name() : null;
                    if (cname.equals(rname)) { cmp = b; break; }
                }
            }
        }
        if (cmp == null) return v;
        if (cmp.op() != IRInstruction.BinaryOp.Op.EQ && cmp.op() != IRInstruction.BinaryOp.Op.NEQ) return v;

        // Vind de referentie-zijde en de null-zijde.
        IRValue refSide = null;
        boolean leftIsNull = cmp.right() instanceof IRValue.ConstNull;
        boolean rightIsNull = cmp.left() instanceof IRValue.ConstNull;
        if (leftIsNull) refSide = cmp.right();
        else if (rightIsNull) refSide = cmp.left();
        else return v;

        final String refName = valueName(refSide);
        if (refName == null) return v;

        final var result = new LinkedHashMap<>(v);
        boolean isEq = cmp.op() == IRInstruction.BinaryOp.Op.EQ;
        // truthValue: true-branch genomen
        // conditie "ref == null": true-branch => ref is NULL; false-branch => NON_NULL
        // conditie "ref != null": true-branch => NON_NULL; false-branch => NULL
        Nullness refined;
        if (isEq) {
            refined = truthValue ? Nullness.NULL : Nullness.NON_NULL;
        } else {
            refined = truthValue ? Nullness.NON_NULL : Nullness.NULL;
        }
        result.put(refName, refined);
        return result;
    }

    private String valueName(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }
}
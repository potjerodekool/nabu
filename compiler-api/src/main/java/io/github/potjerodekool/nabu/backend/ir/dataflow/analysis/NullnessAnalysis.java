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
        // The join of a map with itself is that map: most joins on a straight path are of equal maps.
        if (a == b || a.equals(b)) return a;
        // One pass, one map: a variable that only one side knows is UNKNOWN (join with the UNKNOWN of the other
        // side), so only the variables of both sides need a join. The order of the keys is that of a, then the new
        // ones of b. (This was a copy of a, a set of all keys and a put per key; about 40% of the time of a build of
        // a large program was spent there.)
        final var r = new LinkedHashMap<String, Nullness>((int) ((a.size() + b.size()) / 0.75f) + 1);
        for (final var e : a.entrySet()) {
            final Nullness vb = b.get(e.getKey());
            // join (may): if either unknown -> unknown; null+nonnull -> unknown
            r.put(e.getKey(), vb == null ? Nullness.UNKNOWN : join(e.getValue(), vb));
        }
        for (final var k : b.keySet()) {
            if (!a.containsKey(k)) r.put(k, Nullness.UNKNOWN);
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
        // Only a Temp is refined, and only when the comparison is in the predecessor block: a Temp is defined once, so
        // what the comparison shows holds for the use in the successor (a Named variable may be assigned again).
        final RefineTarget t = refineTarget(predecessorBlock, condition);
        if (t == null) return v;
        final var result = new LinkedHashMap<>(v);
        result.put(t.name(), t.refined(truthValue));
        return result;
    }

    /** What a null comparison that decides a conditional branch says about a Temp. */
    public record RefineTarget(String name, boolean isEq) {
        /** The nullness of the name on the edge that is taken when the condition is {@code truthValue}. */
        public Nullness refined(boolean truthValue) {
            // "ref == null": true edge => NULL, false edge => NON_NULL; "ref != null": the other way around
            return (isEq == truthValue) ? Nullness.NULL : Nullness.NON_NULL;
        }
    }

    /**
     * The Temp that {@code condition} (a Temp defined by {@code ref == null} or {@code ref != null} in {@code block})
     * compares with null, or null when the condition is something else or the Temp is assigned after the comparison.
     */
    public static RefineTarget refineTarget(IRBasicBlock block, IRValue condition) {
        if (!(condition instanceof IRValue.Temp ct)) return null;
        final var instrs = block.instructions();
        int at = -1;
        for (int i = 0; i < instrs.size(); i++) {
            final var instr = instrs.get(i);
            if (instr instanceof IRInstruction.BinaryOp && instr.result() instanceof IRValue.Temp t
                    && ct.name().equals(t.name())) {
                at = i;
                break;
            }
        }
        if (at < 0) return null;
        final var cmp = (IRInstruction.BinaryOp) instrs.get(at);
        if (cmp.op() != IRInstruction.BinaryOp.Op.EQ && cmp.op() != IRInstruction.BinaryOp.Op.NEQ) return null;
        final IRValue ref;
        if (cmp.right() instanceof IRValue.ConstNull) ref = cmp.left();
        else if (cmp.left() instanceof IRValue.ConstNull) ref = cmp.right();
        else return null;
        if (!(ref instanceof IRValue.Temp rt)) return null;
        for (int i = at + 1; i < instrs.size(); i++) {
            if (instrs.get(i).result() instanceof IRValue.Temp t && t.name().equals(rt.name())) return null;
        }
        return new RefineTarget(rt.name(), cmp.op() == IRInstruction.BinaryOp.Op.EQ);
    }

    private String valueName(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }
}
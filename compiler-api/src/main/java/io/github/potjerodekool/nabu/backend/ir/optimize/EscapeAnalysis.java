package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Conservative escape analysis: decides which HeapAlloc results never leave their function. Such allocations
 * are candidates for stack allocation / scalar replacement.
 *
 * <p>Sound by construction: a value escapes unless every use (also through aliases made by Cast, Move and
 * pointer arithmetic) is a plain memory access through it, a pointer comparison, or a call to a known callee
 * whose parameter is itself used only like that (up to a small depth). Anything unknown counts as escaping.
 * Allocations outside the entry block never qualify: they may sit in a loop, where an alloca grows the stack.
 */
public class EscapeAnalysis {

    public static final class Result {
        /** Temp names of HeapAlloc results that do not escape. */
        public final Set<String> nonEscaping = new HashSet<>();
        /** Total number of HeapAllocs in the function. */
        public int totalAllocs;
    }

    public static Result analyze(final IRFunction function) {
        return analyze(function, Map.of());
    }

    public static Result analyze(final IRFunction function, final Map<String, IRFunction> callees) {
        final Result result = new Result();

        final Set<String> allocTemps = new HashSet<>();
        final Set<String> inEntry = new HashSet<>();
        boolean first = true;
        for (final IRBasicBlock b : function.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.HeapAlloc ha) {
                    final String n = nameOf(ha.result());
                    if (n != null) {
                        allocTemps.add(n);
                        result.totalAllocs++;
                        if (first) inEntry.add(n);
                    }
                }
            }
            first = false;
        }

        for (final String a : allocTemps) {
            if (inEntry.contains(a) && !valueEscapes(function, a, callees, 0, new HashSet<>())) {
                result.nonEscaping.add(a);
            }
        }
        return result;
    }

    /**
     * Whether the value named {@code root} (a HeapAlloc result in {@code f}, or a parameter of {@code f}) can
     * outlive the function or be stored somewhere.
     */
    private static boolean valueEscapes(final IRFunction f, final String root,
                                        final Map<String, IRFunction> callees, final int depth,
                                        final Set<String> visiting) {
        final Set<String> alias = new HashSet<>();
        alias.add(root);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (final IRBasicBlock b : f.blocks()) {
                for (final IRInstruction in : b.instructions()) {
                    final IRValue res;
                    final boolean derives;
                    if (in instanceof IRInstruction.Cast c) {
                        res = c.result();
                        derives = namesAny(c.source(), alias);
                    } else if (in instanceof IRInstruction.Move m) {
                        res = m.result();
                        derives = namesAny(m.value(), alias);
                    } else if (in instanceof IRInstruction.BinaryOp bo && isPointerArithmetic(bo)) {
                        res = bo.result();
                        derives = namesAny(bo.left(), alias) || namesAny(bo.right(), alias);
                    } else {
                        continue;
                    }
                    final String rn = nameOf(res);
                    if (derives && rn != null && alias.add(rn)) grew = true;
                }
            }
        }

        for (final IRBasicBlock b : f.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.Cast || in instanceof IRInstruction.Move
                        || in instanceof IRInstruction.HeapAlloc || in instanceof IRInstruction.StackAlloc
                        || in instanceof IRInstruction.Load || in instanceof IRInstruction.ArrayLoad
                        || in instanceof IRInstruction.ArrayLength || in instanceof IRInstruction.InstanceOf) {
                    continue; // aliases are handled above; the others only read through the pointer
                }
                if (in instanceof IRInstruction.BinaryOp bo) {
                    if (isPointerArithmetic(bo)) continue;
                    if (namesAny(bo.left(), alias) || namesAny(bo.right(), alias)) {
                        final var op = bo.op();
                        if (op != IRInstruction.BinaryOp.Op.EQ && op != IRInstruction.BinaryOp.Op.NEQ) return true;
                    }
                    continue;
                }
                if (in instanceof IRInstruction.Store st) {
                    if (namesAny(st.value(), alias)) return true;
                    continue;
                }
                if (in instanceof IRInstruction.ArrayStore as) {
                    if (namesAny(as.value(), alias)) return true;
                    continue;
                }
                if (in instanceof IRInstruction.Call c) {
                    final IRFunction callee = callees.get(c.function());
                    for (int ai = 0; ai < c.args().size(); ai++) {
                        if (!namesAny(c.args().get(ai), alias)) continue;
                        if (callee == null || depth >= 3 || ai >= callee.params.size()) return true;
                        final String key = callee.name + "#" + ai;
                        if (!visiting.add(key)) return true;
                        try {
                            final String pn = nameOf(callee.params.get(ai));
                            if (pn == null || valueEscapes(callee, pn, callees, depth + 1, visiting)) return true;
                        } finally {
                            visiting.remove(key);
                        }
                    }
                    continue;
                }
                if (mentionsAlias(in, alias)) return true;
            }
        }
        return false;
    }

    private static boolean isPointerArithmetic(final IRInstruction.BinaryOp bo) {
        return bo.op() == IRInstruction.BinaryOp.Op.ADD || bo.op() == IRInstruction.BinaryOp.Op.SUB;
    }

    /** Uses by the kinds of instruction that make a value escape. */
    private static boolean mentionsAlias(final IRInstruction in, final Set<String> alias) {
        if (in instanceof IRInstruction.Return r) return namesAny(r.value(), alias);
        if (in instanceof IRInstruction.IndirectCall ic) {
            if (namesAny(ic.callee(), alias)) return true;
            for (final IRValue a : ic.args()) if (namesAny(a, alias)) return true;
            return false;
        }
        if (in instanceof IRInstruction.Phi p) {
            for (final var inc : p.incomingValues()) if (namesAny(inc.value(), alias)) return true;
            return false;
        }
        if (in instanceof IRInstruction.Throw t) return namesAny(t.result(), alias);
        if (in instanceof IRInstruction.MonitorEnter me) return namesAny(me.object(), alias);
        if (in instanceof IRInstruction.MonitorExit mx) return namesAny(mx.object(), alias);
        if (in instanceof IRInstruction.Pop p) return namesAny(p.result(), alias);
        return false;
    }

    private static boolean namesAny(final IRValue v, final Set<String> names) {
        if (v == null) return false;
        if (v instanceof IRValue.Temp t) return names.contains(t.name());
        if (v instanceof IRValue.Named n) return names.contains(n.name());
        if (v instanceof IRValue.Values vals) {
            for (final IRValue inner : vals.values()) if (namesAny(inner, names)) return true;
        }
        return false;
    }

    private static String nameOf(final IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }
}

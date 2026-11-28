package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Conservative escape analysis: decides which HeapAlloc results never leave their function. Such allocations
 * are candidates for stack allocation / scalar replacement.
 *
 * <p>Sound by construction: a value escapes unless every use (also through aliases made by Cast, Move and
 * pointer arithmetic) is a plain memory access through it, a pointer comparison, or a call to a known callee
 * whose parameter is itself used only like that (up to a small depth). Anything unknown counts as escaping.
 * Allocations in a loop never qualify: one stack slot would serve every iteration.
 */
public class EscapeAnalysis {

    public static final class Result {
        /** Temp names of HeapAlloc results that do not escape. */
        public final Set<String> nonEscaping = new HashSet<>();
        /** Total number of HeapAllocs in the function. */
        public int totalAllocs;
    }

    /** Why allocations escape (first reason found), counted when -Dnabu.escapeStats is set. */
    public static final java.util.Map<String, Integer> REASONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<String> LAST = new ThreadLocal<>();
    /** Allocations of other blocks than the entry block, which never qualify. */
    public static int notInEntry;

    private static boolean esc(final String reason) {
        LAST.set(reason);
        return true;
    }

    private static final int MAX_DEPTH = 8;

    public static Result analyze(final IRFunction function) {
        return analyze(function, Map.of());
    }

    public static Result analyze(final IRFunction function, final Map<String, IRFunction> callees) {
        final Result result = new Result();

        final Set<String> allocTemps = new HashSet<>();
        final Map<String, String> blockOf = new HashMap<>();
        final Map<String, String> exactOf = new HashMap<>();
        for (final IRBasicBlock b : function.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.HeapAlloc ha) {
                    final String n = nameOf(ha.result());
                    if (n != null) {
                        allocTemps.add(n);
                        result.totalAllocs++;
                        blockOf.put(n, b.label());
                        exactOf.put(n, exactClass(ha.allocType()));
                    }
                }
            }
        }
        // An allocation in a loop may not qualify: the one slot (the backend puts the alloca in the entry block) would
        // be reused by every iteration, while a value of an earlier iteration can still be in use (kept in a local slot).
        final Set<String> loopBlocks = allocTemps.isEmpty() ? Set.of() : Inliner.computeLoopBlocks(function);
        final Set<String> inEntry = new HashSet<>();
        for (final String a : allocTemps) {
            if (!loopBlocks.contains(blockOf.get(a))) inEntry.add(a);
        }

        TOP.set(function.name);
        final boolean stats = Boolean.getBoolean("nabu.escapeStats");
        for (final String a : allocTemps) {
            if (!inEntry.contains(a)) {
                if (stats) REASONS.merge("(in a loop)", 1, Integer::sum);
                continue;
            }
            LAST.remove();
            if (!valueEscapes(function, a, callees, 0, new HashSet<>(), exactOf.get(a))) {
                result.nonEscaping.add(a);
            } else if (stats) {
                REASONS.merge(String.valueOf(LAST.get()), 1, Integer::sum);
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
                                        final Set<String> visiting, final String exact) {
        final java.util.function.Function<String, Boolean> e = r -> esc(depth > 0 ? r + " in " + f.name : r);
        final Set<String> alias = new HashSet<>();
        alias.add(root);
        // The bytecode lowering spills a parameter into a local slot (an Alloca that is only loaded and stored):
        // a store into such a slot does not make the value escape, the loads from the slot are aliases of it.
        final Set<String> slots = plainSlots(f);
        final Set<String> holding = new HashSet<>();
        // The values that are certainly this very object (copies and casts of it, and loads from a slot that has
        // exactly this value as its only store): only for those is the exact class known. A slot that holds
        // several values (javac reuses local slots) makes a loaded value an alias, but of unknown class.
        final Set<String> precise = new HashSet<>();
        precise.add(root);
        final Map<String, Integer> storesInSlot = new HashMap<>();
        for (final IRBasicBlock b : f.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.Store st && slots.contains(nameOf(st.ptr()))) {
                    storesInSlot.merge(nameOf(st.ptr()), 1, Integer::sum);
                }
            }
        }
        final Set<String> preciseSlots = new HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (final IRBasicBlock b : f.blocks()) {
                for (final IRInstruction in : b.instructions()) {
                    final IRValue res;
                    final boolean derives;
                    if (in instanceof IRInstruction.Store st && slots.contains(nameOf(st.ptr()))) {
                        if (namesAny(st.value(), alias) && holding.add(nameOf(st.ptr()))) grew = true;
                        if (namesAny(st.value(), precise) && storesInSlot.get(nameOf(st.ptr())) == 1
                                && preciseSlots.add(nameOf(st.ptr()))) grew = true;
                        continue;
                    }
                    if (in instanceof IRInstruction.Load ld && holding.contains(nameOf(ld.ptr()))) {
                        res = ld.result();
                        derives = true;
                        if (preciseSlots.contains(nameOf(ld.ptr())) && nameOf(res) != null && precise.add(nameOf(res))) grew = true;
                    } else if (in instanceof IRInstruction.Cast c) {
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
                    if (derives && rn != null && (in instanceof IRInstruction.Cast c2 && namesAny(c2.source(), precise)
                            || in instanceof IRInstruction.Move m2 && namesAny(m2.value(), precise))
                            && precise.add(rn)) grew = true;
                }
            }
        }

        // The source front end leaves the receiver of a constructor call implicit: the emitter pairs it with the
        // most recent allocation that has no constructor call yet (or, for this(..)/super(..), with 'this').
        final Deque<String> pending = new ArrayDeque<>();
        String lastAlloc = null;
        final String ownThis = f.params.isEmpty() ? null : nameOf(f.params.get(0));
        for (final IRBasicBlock b : f.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.HeapAlloc ha && nameOf(ha.result()) != null) {
                    pending.addLast(nameOf(ha.result()));
                    lastAlloc = nameOf(ha.result());
                } else if (in instanceof IRInstruction.StackAlloc sa && nameOf(sa.result()) != null) {
                    pending.addLast(nameOf(sa.result()));
                    lastAlloc = nameOf(sa.result());
                }
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
                        if (op != IRInstruction.BinaryOp.Op.EQ && op != IRInstruction.BinaryOp.Op.NEQ) return e.apply("arithmetic");
                    }
                    continue;
                }
                if (in instanceof IRInstruction.Store st) {
                    if (slots.contains(nameOf(st.ptr()))) continue;
                    if (namesAny(st.value(), alias)) return e.apply("stored in memory");
                    continue;
                }
                if (in instanceof IRInstruction.ArrayStore as) {
                    if (namesAny(as.value(), alias)) return e.apply("stored in array");
                    continue;
                }
                if (in instanceof IRInstruction.Call c) {
                    IRFunction callee = callees.get(c.function());
                    // The receiver is the object that was allocated here, so its class is known exactly: a virtual or
                    // interface call on it goes to the method of that class, which is not overridden by anything.
                    boolean devirtualized = false;
                    if (exact != null && !c.args().isEmpty() && namesAny(c.args().get(0), precise)
                            && (c.callKind() == io.github.potjerodekool.nabu.backend.ir.CallKind.VIRTUAL
                            || c.callKind() == io.github.potjerodekool.nabu.backend.ir.CallKind.INTERFACE)) {
                        final IRFunction exactTarget = resolveExact(exact, c, callees);
                        if (exactTarget != null) {
                            callee = exactTarget;
                            devirtualized = true;
                        }
                    }
                    final boolean ctor = c.callKind() == io.github.potjerodekool.nabu.backend.ir.CallKind.SPECIAL
                            && isInit(c.function());
                    // The callee says what it takes: when the call passes as many arguments as the constructor has
                    // parameters, the receiver is among them (bytecode lowering writes paramTypes with and without the
                    // receiver); one argument less means that the receiver is implicit (source front end).
                    final boolean explicitReceiver = callee != null
                            ? c.args().size() == callee.params.size()
                            : c.paramTypes() != null && c.args().size() == c.paramTypes().size() + 1;
                    final boolean implicit = ctor && !explicitReceiver;
                    String receiver = null;
                    if (ctor) {
                        if (explicitReceiver) {
                            if (!c.args().isEmpty()) pending.removeLastOccurrence(nameOf(c.args().get(0)));
                        } else {
                            receiver = pending.pollLast();
                            if (receiver == null) {
                                receiver = (!c.args().isEmpty() && nameOf(c.args().get(0)) != null
                                        && nameOf(c.args().get(0)).equals(ownThis)) ? null : lastAlloc;
                                if (receiver == null) receiver = ownThis;
                            }
                        }
                    }
                    final boolean unknownTarget = callee == null || depth >= MAX_DEPTH
                            || (!devirtualized
                            && (c.callKind() == io.github.potjerodekool.nabu.backend.ir.CallKind.VIRTUAL
                            || c.callKind() == io.github.potjerodekool.nabu.backend.ir.CallKind.INTERFACE));
                    if (implicit && receiver != null && alias.contains(receiver)) {
                        if (unknownTarget || paramEscapes(callee, 0, callees, depth, visiting, precise.contains(receiver) ? exact : null)) return unknownTarget ? esc("ctor: unknown target") : true;
                    }
                    final int shift = implicit ? 1 : 0;
                    for (int ai = 0; ai < c.args().size(); ai++) {
                        if (!namesAny(c.args().get(ai), alias)) continue;
                        if (unknownTarget) return e.apply(callee == null ? "call: callee unknown" : depth >= MAX_DEPTH ? "call: too deep" : "call: virtual/interface");
                        if (paramEscapes(callee, ai + shift, callees, depth, visiting, namesAny(c.args().get(ai), precise) ? exact : null)) return true;
                    }
                    continue;
                }
                if (mentionsAlias(in, alias)) return e.apply(in.getClass().getSimpleName());
            }
        }
        return false;
    }

    /** Alloca slots of {@code f} that are used only as the pointer of a Load or Store (their address is never taken). */
    private static Set<String> plainSlots(final IRFunction f) {
        final Set<String> slots = new HashSet<>();
        for (final IRBasicBlock b : f.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.Alloca a && nameOf(a.result()) != null) slots.add(nameOf(a.result()));
            }
        }
        if (slots.isEmpty()) return slots;
        final Set<String> bad = new HashSet<>();
        for (final IRBasicBlock b : f.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.Load) continue;
                if (in instanceof IRInstruction.Store st) {
                    markSlots(st.value(), slots, bad);
                    continue;
                }
                for (final java.lang.reflect.Method m : operandAccessors(in.getClass())) {
                    try {
                        final Object o = m.invoke(in);
                        if (o instanceof IRValue v) {
                            markSlots(v, slots, bad);
                        } else if (o instanceof java.util.List<?> l) {
                            for (final Object x : l) {
                                if (x instanceof IRValue v) markSlots(v, slots, bad);
                                else if (x != null && !(x instanceof IRType) && !(x instanceof String)) return Set.of();
                            }
                        }
                    } catch (final ReflectiveOperationException ex) {
                        return Set.of(); // unknown shape: no slots are plain
                    }
                }
            }
        }
        slots.removeAll(bad);
        return slots;
    }

    private static void markSlots(final IRValue v, final Set<String> slots, final Set<String> bad) {
        if (v == null) return;
        final String n = nameOf(v);
        if (n != null && slots.contains(n)) bad.add(n);
        if (v instanceof IRValue.Values vals) for (final IRValue inner : vals.values()) markSlots(inner, slots, bad);
    }

    private static final ClassValue<java.lang.reflect.Method[]> ACCESSORS = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Method[] computeValue(final Class<?> type) {
            final java.util.List<java.lang.reflect.Method> ms = new ArrayList<>();
            if (type.isRecord()) {
                for (final java.lang.reflect.RecordComponent rc : type.getRecordComponents()) {
                    if (rc.getName().equals("result")) continue; // a definition, not a use
                    final Class<?> t = rc.getType();
                    if (IRValue.class.isAssignableFrom(t) || java.util.List.class.isAssignableFrom(t)) {
                        rc.getAccessor().setAccessible(true);
                        ms.add(rc.getAccessor());
                    }
                }
            }
            return ms.toArray(new java.lang.reflect.Method[0]);
        }
    };

    private static java.lang.reflect.Method[] operandAccessors(final Class<?> type) {
        return ACCESSORS.get(type);
    }

    private static final String TRACE_FN = System.getProperty("nabu.escapeTraceFn");
    private static final ThreadLocal<String> TOP = new ThreadLocal<>();

    private static boolean paramEscapes(final IRFunction callee, final int index,
                                         final Map<String, IRFunction> callees, final int depth,
                                         final Set<String> visiting, final String exact) {
        final boolean r = paramEscapes0(callee, index, callees, depth, visiting, exact);
        if (TRACE_FN != null && TRACE_FN.equals(TOP.get())) {
            System.err.println("[nabu/IR] TRACE " + "  ".repeat(depth) + callee.name + "#" + index + " exact=" + exact
                    + " blocks=" + callee.blocks().size() + " -> " + (r ? "ESCAPES" : "ok") + " (" + LAST.get() + ")");
        }
        return r;
    }

    private static boolean paramEscapes0(final IRFunction callee, final int index,
                                         final Map<String, IRFunction> callees, final int depth,
                                         final Set<String> visiting, final String exact) {
        if (index >= callee.params.size()) return true;
        final String key = callee.name + "#" + index;
        if (!visiting.add(key)) return true;
        try {
            final String pn = nameOf(callee.params.get(index));
            return pn == null || valueEscapes(callee, pn, callees, depth + 1, visiting, exact);
        } finally {
            visiting.remove(key);
        }
    }

    /** The dotted class name of an allocation ({@code java.util.ArrayList$Itr}), or null when the IR does not say. */
    private static String exactClass(final io.github.potjerodekool.nabu.backend.ir.types.IRType type) {
        if (type instanceof io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr p && p.jvmDescriptor() != null) {
            final String d = p.jvmDescriptor();
            if (d.length() > 2 && d.charAt(0) == 'L' && d.endsWith(";")) {
                return d.substring(1, d.length() - 1).replace('/', '.');
            }
        }
        return null;
    }

    /**
     * The function of the exact class {@code exact} that a virtual or interface call on an object of that class
     * reaches: the function itself when it names the class, else the function with the same member name in the class
     * (the call names the static type: {@code Iterator_hasNext} for an {@code ArrayList$Itr}). Null when there is not
     * exactly one such function that takes the same number of arguments (the method is inherited, or unknown).
     */
    private static IRFunction resolveExact(final String exact, final IRInstruction.Call c,
                                           final Map<String, IRFunction> callees) {
        final String fn = c.function();
        if (fn.startsWith(exact + "_")) {
            final IRFunction own = callees.get(fn);
            return own != null && own.params.size() == c.args().size() ? own : null;
        }
        IRFunction found = null;
        for (int i = fn.indexOf('_'); i >= 0; i = fn.indexOf('_', i + 1)) {
            final IRFunction cand = callees.get(exact + fn.substring(i));
            if (cand != null && cand.params.size() == c.args().size()) {
                if (found != null) return null;
                found = cand;
            }
        }
        return found;
    }

    /** Same test as the native backend's CallTargets.isInit. */
    private static boolean isInit(final String function) {
        return function.endsWith("_init") || function.contains("_init$");
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

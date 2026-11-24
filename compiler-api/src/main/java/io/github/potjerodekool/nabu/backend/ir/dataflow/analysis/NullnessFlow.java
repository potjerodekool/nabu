package io.github.potjerodekool.nabu.backend.ir.dataflow.analysis;

import io.github.potjerodekool.nabu.backend.ir.Dominators;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowEngine;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowResult;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The nullness analysis of {@link NullnessAnalysis} on dense arrays: the same result, a fraction of the time.
 *
 * <p>The generic engine keeps a {@code LinkedHashMap<String, Nullness>} per block, copies it for every block that is
 * visited and merges two of them for every edge; on a build of the petstore that was a third of the processor time of
 * the whole compiler. Here every name that an instruction defines gets a number once, the state of a block is a
 * {@code byte[]} (0: not known, 1: NULL, 2: NON_NULL, 3: UNKNOWN) and the copy, the merge and the comparison are loops
 * over bytes. The semantics are those of {@link NullnessAnalysis}, including its quirks: an instruction reads the state
 * at the start of its block, a variable that is known on one side of a merge only is UNKNOWN, the blocks that the
 * dominator tree does not reach (exception handlers) keep an empty state. After {@code t == null} or {@code t != null}
 * on a Temp that is defined once, the successor knows t is NULL or NON_NULL (the refinement of that class did nothing
 * before: it looked at the constant instead of the other operand). -Dnabu.nullness.verify=true runs both and reports a
 * difference, -Dnabu.nullness.refine=false switches the refinement off.
 */
public final class NullnessFlow {

    private static final byte NULL = 1, NON_NULL = 2, UNKNOWN = 3;

    private static final int CONST = 0, COPY = 1, PHI = 2;

    private NullnessFlow() {}

    /** The state at the start of the blocks. */
    public static final class Result {
        private final Map<String, Integer> blockIndex;
        private final Map<String, Integer> nameIds;
        private final byte[][] in;
        private final DataFlowResult<Map<String, Nullness>> legacy;

        private Result(Map<String, Integer> blockIndex, Map<String, Integer> nameIds, byte[][] in) {
            this.blockIndex = blockIndex;
            this.nameIds = nameIds;
            this.in = in;
            this.legacy = null;
        }

        private Result(DataFlowResult<Map<String, Nullness>> legacy) {
            this.blockIndex = null;
            this.nameIds = null;
            this.in = null;
            this.legacy = legacy;
        }

        /** The nullness of {@code name} at the start of the block, or null when nothing is known about it. */
        public Nullness inAt(String label, String name) {
            if (legacy != null) {
                final var m = legacy.in(label);
                return m == null ? null : m.get(name);
            }
            final Integer b = blockIndex.get(label);
            final Integer id = nameIds.get(name);
            if (b == null || id == null) {
                return null;
            }
            return decode(in[b][id]);
        }
    }

    private static Nullness decode(byte b) {
        return switch (b) {
            case NULL -> Nullness.NULL;
            case NON_NULL -> Nullness.NON_NULL;
            case UNKNOWN -> Nullness.UNKNOWN;
            default -> null;
        };
    }

    private static byte encode(Nullness n) {
        return switch (n) {
            case NULL -> NULL;
            case NON_NULL -> NON_NULL;
            case UNKNOWN -> UNKNOWN;
        };
    }

    /** One instruction that changes the state. */
    private static final class Op {
        final int kind;
        final int result;
        final byte value;      // CONST: the value; COPY: the value when the source is not known
        final int source;      // COPY: the number of the source, -1 for none
        final int[] phiIds;    // PHI: number per incoming value, -1 for a constant or an unknown name
        final byte[] phiConst; // PHI: the value of a constant

        Op(int kind, int result, byte value, int source, int[] phiIds, byte[] phiConst) {
            this.kind = kind;
            this.result = result;
            this.value = value;
            this.source = source;
            this.phiIds = phiIds;
            this.phiConst = phiConst;
        }
    }

    /** -Dnabu.nullness.refine=false switches the refinement on a null comparison off. */
    private static final boolean REFINE = !"false".equalsIgnoreCase(System.getProperty("nabu.nullness.refine"));
    private static final boolean VERIFY = Boolean.getBoolean("nabu.nullness.verify");
    private static final java.util.concurrent.atomic.AtomicLong VERIFIED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DIFFERENT = new java.util.concurrent.atomic.AtomicLong();

    /** {@link #analyze}, and with -Dnabu.nullness.verify=true a comparison with the reference analysis. */
    public static Result analyzeChecked(IRFunction function) {
        final Result r = analyze(function);
        if (VERIFY) {
            final String d = compareWithReference(function, r);
            final long n = VERIFIED.incrementAndGet();
            if (d != null && DIFFERENT.incrementAndGet() <= 20) {
                System.err.println("[nullness verify] DIFFERENCE " + d);
            }
            if (n % 20000 == 0) {
                System.err.println("[nullness verify] " + n + " functions compared, " + DIFFERENT.get() + " different");
            }
        }
        return r;
    }

    public static Result analyze(IRFunction function) {
        final List<IRBasicBlock> blocks = function.blocks();
        final int n = blocks.size();
        final Map<String, Integer> blockIndex = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            if (blockIndex.putIfAbsent(blocks.get(i).label(), i) != null) {
                // Two blocks with one label: leave it to the engine.
                return new Result(DataFlowEngine.analyze(function, new NullnessAnalysis()));
            }
        }
        if (n == 0) {
            return new Result(blockIndex, Map.of(), new byte[0][]);
        }

        // Numbers for the names that an instruction defines.
        final Map<String, Integer> ids = new HashMap<>();
        final java.util.BitSet redefined = new java.util.BitSet();
        for (final var block : blocks) {
            for (final var instr : block.instructions()) {
                final var res = instr.result();
                final String name = res == null ? null : valueName(res);
                if (name != null) {
                    final Integer old = ids.putIfAbsent(name, ids.size());
                    if (old != null) {
                        redefined.set(old);
                    }
                }
            }
        }
        final int size = ids.size();

        // The instructions, once.
        final Op[][] ops = new Op[n][];
        final var tmp = new java.util.ArrayList<Op>();
        for (int i = 0; i < n; i++) {
            tmp.clear();
            for (final var instr : blocks.get(i).instructions()) {
                final var res = instr.result();
                final String name = res == null ? null : valueName(res);
                if (name == null) {
                    continue;
                }
                final int rid = ids.get(name);
                switch (instr) {
                    case IRInstruction.HeapAlloc ignored -> tmp.add(new Op(CONST, rid, NON_NULL, -1, null, null));
                    case IRInstruction.StackAlloc ignored -> tmp.add(new Op(CONST, rid, NON_NULL, -1, null, null));
                    case IRInstruction.Alloca ignored -> tmp.add(new Op(CONST, rid, NON_NULL, -1, null, null));
                    case IRInstruction.AllocaArray ignored -> tmp.add(new Op(CONST, rid, NON_NULL, -1, null, null));
                    case IRInstruction.Move m -> tmp.add(copy(rid, m.value(), ids));
                    case IRInstruction.Cast c -> tmp.add(copy(rid, c.source(), ids));
                    case IRInstruction.Phi phi -> {
                        final var incoming = phi.incomingValues();
                        final int[] phiIds = new int[incoming.size()];
                        final byte[] phiConst = new byte[incoming.size()];
                        int k = 0;
                        for (final var inc : incoming) {
                            final String inName = valueName(inc.value());
                            if (inName == null) {
                                phiIds[k] = -1;
                                phiConst[k] = (inc.value() instanceof IRValue.ConstNull) ? NULL : NON_NULL;
                            } else {
                                final Integer id = ids.get(inName);
                                phiIds[k] = id == null ? -1 : id;
                                phiConst[k] = UNKNOWN;
                            }
                            k++;
                        }
                        tmp.add(new Op(PHI, rid, UNKNOWN, -1, phiIds, phiConst));
                    }
                    default -> {
                        if (res.type() instanceof IRType.Ptr) {
                            tmp.add(new Op(CONST, rid, encode(pointerResult(instr)), -1, null, null));
                        }
                    }
                }
            }
            ops[i] = tmp.toArray(new Op[0]);
        }

        // The edges.
        final int[][] preds = new int[n][];
        final int[][] succs = new int[n][];
        {
            final var predLists = new java.util.ArrayList<java.util.LinkedHashSet<Integer>>(n);
            for (int i = 0; i < n; i++) predLists.add(new java.util.LinkedHashSet<>());
            final var succLists = new java.util.ArrayList<java.util.ArrayList<Integer>>(n);
            for (int i = 0; i < n; i++) succLists.add(new java.util.ArrayList<>());
            for (int i = 0; i < n; i++) {
                final var t = blocks.get(i).getTerminator();
                if (t instanceof IRInstruction.Branch br) {
                    edge(blockIndex, predLists, succLists, i, br.targetLabel());
                } else if (t instanceof IRInstruction.CondBranch cb) {
                    edge(blockIndex, predLists, succLists, i, cb.trueLabel());
                    edge(blockIndex, predLists, succLists, i, cb.falseLabel());
                }
            }
            for (int i = 0; i < n; i++) {
                preds[i] = predLists.get(i).stream().mapToInt(Integer::intValue).toArray();
                succs[i] = succLists.get(i).stream().mapToInt(Integer::intValue).toArray();
            }
        }

        // The refinement of a null comparison on the edges of a conditional branch: block, the name and the value on
        // the true edge and on the false edge (0: none).
        final int[] refId = new int[n];
        final byte[] refTrue = new byte[n];
        final byte[] refFalse = new byte[n];
        Arrays.fill(refId, -1);
        if (REFINE) {
            for (int i = 0; i < n; i++) {
                if (blocks.get(i).getTerminator() instanceof IRInstruction.CondBranch cb
                        && !cb.trueLabel().equals(cb.falseLabel())) {
                    final var t = NullnessAnalysis.refineTarget(blocks.get(i), cb.condition());
                    final Integer id = t == null ? null : ids.get(t.name());
                    // a Temp that is defined once: what the comparison shows holds for the use in the successor
                    if (id != null && !redefined.get(id)) {
                        refId[i] = id;
                        refTrue[i] = encode(t.refined(true));
                        refFalse[i] = encode(t.refined(false));
                    }
                }
            }
        }

        // The order of the blocks: the preorder of the dominator tree.
        final int[] order = order(function, blockIndex);

        final byte[][] in = new byte[n][];
        final byte[][] out = new byte[n][];
        final byte[] empty = new byte[size];
        for (int i = 0; i < n; i++) {
            in[i] = empty;
            out[i] = empty;
        }

        final var worklist = new ArrayDeque<Integer>();
        for (final int b : order) {
            worklist.add(b);
        }
        while (!worklist.isEmpty()) {
            final int b = worklist.poll();
            byte[] newIn;
            final int[] ps = preds[b];
            if (ps.length == 0) {
                newIn = empty;
            } else {
                newIn = out[ps[0]].clone();
                applyRefine(newIn, ps[0], b, refId, refTrue, refFalse, succs);
                for (int k = 1; k < ps.length; k++) {
                    final int p = ps[k];
                    if (refId[p] < 0) {
                        meet(newIn, out[p]);
                    } else {
                        final byte[] o = out[p].clone();
                        applyRefine(o, p, b, refId, refTrue, refFalse, succs);
                        meet(newIn, o);
                    }
                }
            }
            if (!Arrays.equals(newIn, in[b])) {
                in[b] = newIn;
            }
            final byte[] state = in[b];
            final byte[] newOut = state.clone();
            for (final Op op : ops[b]) {
                newOut[op.result] = switch (op.kind) {
                    case CONST -> op.value;
                    case COPY -> {
                        final byte s = op.source < 0 ? 0 : state[op.source];
                        yield s != 0 ? s : op.value;
                    }
                    default -> phi(op, state);
                };
            }
            if (!Arrays.equals(newOut, out[b])) {
                out[b] = newOut;
                for (final int s : succs[b]) {
                    worklist.add(s);
                }
            }
        }
        return new Result(blockIndex, ids, in);
    }

    /** The state on the edge from p to b: what the comparison at the end of p shows about its name. */
    private static void applyRefine(byte[] state, int p, int b, int[] refId, byte[] refTrue, byte[] refFalse,
                                    int[][] succs) {
        if (refId[p] < 0) {
            return;
        }
        // succs[p] is the true target, then the false target (when it is a block of the function)
        final int[] s = succs[p];
        if (s.length == 2) {
            state[refId[p]] = (s[0] == b) ? refTrue[p] : refFalse[p];
        }
    }

    private static void edge(Map<String, Integer> blockIndex, List<java.util.LinkedHashSet<Integer>> preds,
                             List<java.util.ArrayList<Integer>> succs, int from, String targetLabel) {
        final Integer to = blockIndex.get(targetLabel);
        if (to == null) {
            return;
        }
        preds.get(to).add(from);
        succs.get(from).add(to);
    }

    private static Op copy(int rid, IRValue source, Map<String, Integer> ids) {
        final String sn = valueName(source);
        final Integer sid = sn == null ? null : ids.get(sn);
        return new Op(COPY, rid, encode(defaultNullness(source)), sid == null ? -1 : sid, null, null);
    }

    private static byte phi(Op op, byte[] state) {
        byte meet = 0;
        boolean any = false;
        for (int k = 0; k < op.phiIds.length; k++) {
            final byte v;
            if (op.phiIds[k] < 0) {
                v = op.phiConst[k];
            } else {
                final byte s = state[op.phiIds[k]];
                v = s != 0 ? s : UNKNOWN;
            }
            if (!any) {
                meet = v;
                any = true;
            } else if (meet != v) {
                meet = UNKNOWN;
            }
        }
        return any ? meet : UNKNOWN;
    }

    /** a := the meet of a and b: known on both sides gives the join, known on one side only gives UNKNOWN. */
    private static void meet(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++) {
            final byte x = a[i], y = b[i];
            if (x == y) {
                continue;
            }
            a[i] = UNKNOWN; // different values, or known on one side only
        }
    }

    private static int[] order(IRFunction function, Map<String, Integer> blockIndex) {
        List<String> labels = null;
        try {
            final var po = Dominators.compute(function).getPreorder();
            if (!po.isEmpty()) {
                labels = po;
            }
        } catch (Exception ignored) {
            // the order of the blocks then
        }
        if (labels == null) {
            final int[] all = new int[function.blocks().size()];
            for (int i = 0; i < all.length; i++) all[i] = i;
            return all;
        }
        final int[] result = new int[labels.size()];
        int k = 0;
        for (final String l : labels) {
            final Integer i = blockIndex.get(l);
            if (i != null) {
                result[k++] = i;
            }
        }
        return k == result.length ? result : Arrays.copyOf(result, k);
    }

    /** The nullness of a pointer that an instruction other than an allocation or a copy returns. */
    private static Nullness pointerResult(IRInstruction instr) {
        if (instr instanceof IRInstruction.Call c) {
            final String fn = c.function();
            final Nullness rn = fn == null ? null : NullnessAnalysis.getReturnNullness(fn);
            if (rn != null) {
                return rn;
            }
            if (fn != null && (fn.contains("<init>") || fn.contains("alloc") || fn.contains("Alloc")
                    || fn.toLowerCase().contains("new"))) {
                return Nullness.NON_NULL;
            }
        }
        return Nullness.UNKNOWN;
    }

    private static Nullness defaultNullness(IRValue v) {
        if (v instanceof IRValue.ConstNull) return Nullness.NULL;
        if (v instanceof IRValue.ConstString || v instanceof IRValue.ConstClass) return Nullness.NON_NULL;
        if (v instanceof IRValue.Temp || v instanceof IRValue.Named) return Nullness.UNKNOWN;
        return Nullness.NON_NULL;
    }

    private static String valueName(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }

    /**
     * Compares with {@link NullnessAnalysis} through the engine and returns a description of the first difference, or
     * null.
     */
    public static String compareWithReference(IRFunction function, Result result) {
        final var ref = DataFlowEngine.analyze(function, new NullnessAnalysis());
        for (final var block : function.blocks()) {
            final var m = ref.in(block.label());
            if (m == null) {
                continue;
            }
            for (final var e : m.entrySet()) {
                final Nullness mine = result.inAt(block.label(), e.getKey());
                if (mine != e.getValue()) {
                    return function.name + " block " + block.label() + " " + e.getKey() + ": reference "
                            + e.getValue() + ", dense " + mine;
                }
            }
            if (result.legacy == null) {
                final byte[] row = result.in[result.blockIndex.get(block.label())];
                for (final var e : result.nameIds.entrySet()) {
                    if (row[e.getValue()] != 0 && !m.containsKey(e.getKey())) {
                        return function.name + " block " + block.label() + " " + e.getKey() + ": reference absent, dense "
                                + decode(row[e.getValue()]);
                    }
                }
            }
        }
        return null;
    }
}

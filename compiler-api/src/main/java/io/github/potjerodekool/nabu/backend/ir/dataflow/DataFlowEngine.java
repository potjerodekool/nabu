package io.github.potjerodekool.nabu.backend.ir.dataflow;

import io.github.potjerodekool.nabu.backend.ir.Dominators;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;

import java.util.*;

/**
 * Iteratieve worklist-oplosser voor dataflow-analyses.
 *
 * <p>Gebruikt de dominator-boom (voorwaarts) of de post-dominator-boom
 * (achterwaarts) om blokken in een gunstige volgorde te verwerken, wat het
 * aantal iteraties tot fixpoint verkleint.
 */
public final class DataFlowEngine {

    private DataFlowEngine() {}

    /**
     * Voert de gegeven analyse uit op de functie en levert IN/OUT per blok.
     */
    public static <V> DataFlowResult<V> analyze(IRFunction function,
                                                DataFlowAnalysis<V> analysis) {
        // Cache-lookup
        final var cached = DataFlowCache.get(function, analysis.getClass());
        if (cached != null) {
            @SuppressWarnings("unchecked")
            final DataFlowResult<V> r = (DataFlowResult<V>) cached;
            return r;
        }

        final var blocks = function.blocks();
        if (blocks.isEmpty()) {
            return new DataFlowResult<>(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
        final Map<String, V> in = new LinkedHashMap<>();
        final Map<String, V> out = new LinkedHashMap<>();
        final Map<String, Set<String>> preds = Dominators.computePredecessors(blocks);
        final Map<String, List<String>> succs = buildSuccessors(blocks);
        // Dominator-info voor back-edge-detectie (widening).
        Dominators dominators = null;
        try { dominators = Dominators.compute(function); } catch (Exception ignored) {}

        // Initialisatie
        final V boundary = analysis.boundaryValue(function);
        final V initial = analysis.initialValue();
        final String entry = function.entryBlock().label();

        for (final var b : blocks) {
            in.put(b.label(), initial);
            out.put(b.label(), initial);
        }

        // Boundary: forward -> entry IN = boundary; backward -> exit OUT = boundary
        if (analysis.direction() == DataFlowDirection.FORWARD) {
            in.put(entry, boundary);
        } else {
            // exit blocks: blocks without successors
            for (final var b : blocks) {
                if (succs.getOrDefault(b.label(), List.of()).isEmpty()) {
                    out.put(b.label(), boundary);
                }
            }
        }

        // Worklist
        final var worklist = new ArrayDeque<String>();
        for (final var b : blocks) {
            worklist.add(b.label());
        }

        // Order hint via dominators (forward) / post-dominators (backward)
        final List<String> order = analysis.direction() == DataFlowDirection.FORWARD
                ? dominanceOrder(function)
                : reverseDominanceOrder(function);
        // Re-seed worklist in preferred order
        worklist.clear();
        for (final var l : order) worklist.add(l);

        while (!worklist.isEmpty()) {
            final var label = worklist.poll();
            final IRBasicBlock block = findBlock(blocks, label);
            if (block == null) continue;

            if (analysis.direction() == DataFlowDirection.FORWARD) {
                final var predSet = preds.getOrDefault(label, Set.of());
                V newIn;
                if (predSet.isEmpty()) {
                    newIn = label.equals(entry) ? boundary : initial;
                } else {
                    boolean first = true;
                    newIn = null;
                    for (final var p : predSet) {
                        V pv = out.get(p);
                        // Edge-refinement: verfijn op basis van de terminator van p
                        final var pb = findBlock(blocks, p);
                        if (pb != null) {
                            final var t = pb.getTerminator();
                            if (t instanceof io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction.CondBranch cb) {
                                final boolean truthValue = cb.trueLabel().equals(label);
                                pv = analysis.refine(pv, pb, cb.condition(), truthValue);
                            }
                        }
                        boolean backEdge = dominators != null && dominators.dominates(p, label) && !p.equals(label);
                        if (first) { newIn = pv; first = false; }
                        else if (backEdge) { newIn = analysis.widen(newIn, pv); }
                        else newIn = analysis.meet(newIn, pv);
                    }
                    if (newIn == null) newIn = initial;
                }
                if (!analysis.equalsValue(newIn, in.get(label))) {
                    in.put(label, newIn);
                }
                final V newOut = analysis.transfer(block, in.get(label));
                if (!analysis.equalsValue(newOut, out.get(label))) {
                    out.put(label, newOut);
                    for (final var s : succs.getOrDefault(label, List.of())) {
                        worklist.add(s);
                    }
                }
            } else {
                // BACKWARD: OUT = meet of successors' IN; IN = transfer(block, OUT)
                V newOut = analysis.initialValue();
                boolean first = true;
                final var ss = succs.getOrDefault(label, List.of());
                if (ss.isEmpty()) {
                    newOut = boundary;
                } else {
                    for (final var s : ss) {
                        if (first) { newOut = in.get(s); first = false; }
                        else newOut = analysis.meet(newOut, in.get(s));
                    }
                }
                if (!analysis.equalsValue(newOut, out.get(label))) {
                    out.put(label, newOut);
                }
                final V newIn = analysis.transfer(block, out.get(label));
                if (!analysis.equalsValue(newIn, in.get(label))) {
                    in.put(label, newIn);
                    for (final var p : preds.getOrDefault(label, Set.of())) {
                        worklist.add(p);
                    }
                }
            }
        }

        final var result = new DataFlowResult<>(in, out);
        DataFlowCache.put(function, analysis.getClass(), result);
        return result;
    }

    private static List<String> dominanceOrder(IRFunction function) {
        try {
            final var dom = Dominators.compute(function);
            final var po = dom.getPreorder();
            if (!po.isEmpty()) return new ArrayList<>(po);
        } catch (Exception ignored) {}
        return function.blocks().stream().map(IRBasicBlock::label).toList();
    }

    private static List<String> reverseDominanceOrder(IRFunction function) {
        final var order = dominanceOrder(function);
        final var rev = new ArrayList<>(order);
        Collections.reverse(rev);
        return rev;
    }

    private static Map<String, List<String>> buildSuccessors(List<IRBasicBlock> blocks) {
        final Map<String, List<String>> succs = new LinkedHashMap<>();
        for (final var b : blocks) succs.put(b.label(), new ArrayList<>());
        for (final var b : blocks) {
            final var t = b.getTerminator();
            if (t instanceof io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction.Branch br) {
                succs.get(b.label()).add(br.targetLabel());
            } else if (t instanceof io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction.CondBranch cb) {
                succs.get(b.label()).add(cb.trueLabel());
                succs.get(b.label()).add(cb.falseLabel());
            }
        }
        return succs;
    }

    private static IRBasicBlock findBlock(List<IRBasicBlock> blocks, String label) {
        for (final var b : blocks) if (b.label().equals(label)) return b;
        return null;
    }
}
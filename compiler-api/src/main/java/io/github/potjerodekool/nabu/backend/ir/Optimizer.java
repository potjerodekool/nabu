package io.github.potjerodekool.nabu.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.optimize.EscapeAnalysis;
import io.github.potjerodekool.nabu.backend.ir.optimize.StackAllocation;
import io.github.potjerodekool.nabu.backend.ir.optimize.ScalarReplacement;
import io.github.potjerodekool.nabu.backend.ir.optimize.Inliner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Optimizer {

    /**
     * Cross-module optimalisatie: bouwt een globale callee-map over alle modules en
     * inlint triviale callees over module-grenzen heen, gevolgd door de per-functie passes.
     */
    public static List<IRModule> optimizeAll(final List<IRModule> modules) {
        // Stap 0: TypeConsistency + PhiElimination op alle functies, zodat callees
        // Phi-vrij zijn en de inliner multi-blok callees kan handelen.
        for (final IRModule m : modules) {
            for (final IRFunction f : m.functions()) {
                if (f.isExternal()) continue;
                io.github.potjerodekool.nabu.backend.ir.optimize.TypeConsistency.run(f);
                io.github.potjerodekool.nabu.backend.ir.PhiElimination.run(f);
            }
        }

        // Globale callee-map.
        final Map<String, IRFunction> globalCallees = new HashMap<>();
        for (final IRModule m : modules) {
            for (final IRFunction f : m.functions()) {
                if (!f.isExternal()) globalCallees.put(f.name, f);
            }
        }
        // Call-site telling over alle modules.
        final Map<String, Integer> globalCallCounts = new HashMap<>();
        for (final IRModule m : modules) {
            for (final IRFunction f : m.functions()) {
                if (f.isExternal()) continue;
                for (final IRBasicBlock b : f.blocks()) {
                    for (final IRInstruction in : b.instructions()) {
                        if (in instanceof IRInstruction.Call c) {
                            globalCallCounts.merge(c.function(), 1, Integer::sum);
                        }
                    }
                }
            }
        }

        // Escape-analyse (analyse-only): rapporteer niet-ontsnappende allocaties.
        if (Boolean.getBoolean("nabu.escapeStats")) {
            int total = 0, nonEsc = 0;
            for (final IRModule m : modules) {
                for (final IRFunction f : m.functions()) {
                    if (f.isExternal()) continue;
                    final EscapeAnalysis.Result r = EscapeAnalysis.analyze(f, globalCallees);
                    total += r.totalAllocs;
                    nonEsc += r.nonEscaping.size();
                }
            }
            System.err.println("[nabu/IR] escape analysis: " + nonEsc + " of " + total
                    + " HeapAllocs do not escape their method");
        }

        final Inliner globalInliner = new Inliner(globalCallees, globalCallCounts);

        // Inline in elke functie van elke module (mutates in place).
        for (final IRModule m : modules) {
            for (final IRFunction f : m.functions()) {
                if (f.isExternal()) continue;
                globalInliner.run(f);
            }
        }

        // Stack-allocation van niet-ontsnappende allocs (opt-in).
        final List<IRModule> stackModules = new ArrayList<>();
        for (final IRModule m : modules) {
            stackModules.add(StackAllocation.run(m, globalCallees));
        }

        // Scalar replacement van niet-ontsnappende allocs (opt-in, single-block).
        final List<IRModule> scalarModules = new ArrayList<>();
        for (final IRModule m : stackModules) {
            scalarModules.add(ScalarReplacement.run(m, globalCallees));
        }
        // use scalarModules for per-function passes
        modules.clear();
        modules.addAll(scalarModules);

        // Interprocedurale nullness: bereken return-nullness per functie.
        for (final IRModule m : modules) {
            for (final IRFunction f : m.functions()) {
                if (f.isExternal()) continue;
                try {
                    final var res = io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowEngine.analyze(
                            f, new io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.NullnessAnalysis());
                    // Vind return-instructies
                    io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Nullness rn =
                        io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Nullness.UNKNOWN;
                    boolean any = false;
                    for (final var b : f.blocks()) {
                        for (final var instr : b.instructions()) {
                            if (instr instanceof io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction.Return r
                                    && r.value() != null) {
                                final String vname = (r.value() instanceof io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp t)
                                        ? t.name()
                                        : (r.value() instanceof io.github.potjerodekool.nabu.backend.ir.values.IRValue.Named n) ? n.name() : null;
                                if (vname != null) {
                                    final var m2 = res.in(b.label());
                                    if (m2 != null) {
                                        final var nv = m2.get(vname);
                                        if (nv != null) {
                                            if (!any) { rn = nv; any = true; }
                                            else if (rn != nv) rn = io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Nullness.UNKNOWN;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.NullnessAnalysis.setReturnNullness(f.name, rn);
                } catch (Exception ignored) {}
            }
        }

        // Invalideer dataflow-cache voor alle functies (IR is gemuteerd).
        for (final IRModule m : modules) {
            for (final IRFunction f : m.functions()) {
                io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowCache.invalidate(f);
            }
        }

        // Per-functie passes.
        final List<IRModule> result = new ArrayList<>();
        for (final IRModule m : stackModules) {
            final var optFns = m.functions().stream()
                    .map(f -> f.isExternal() ? f : Optimizer.optimize(f))
                    .toList();
            result.add(m.withFunctions(optFns));
        }
        return result;
    }

    public static IRModule optimize(final IRModule module) {
        // Bouw een callee-map voor inlining (alleen functies in deze module).
        final Map<String, IRFunction> calleeMap = new HashMap<>();
        for (final IRFunction f : module.functions()) {
            if (!f.isExternal()) calleeMap.put(f.name, f);
        }

        // Tel call-sites per callee (voor single-use heuristiek).
        final Map<String, Integer> callCounts = new HashMap<>();
        for (final IRFunction f : module.functions()) {
            if (f.isExternal()) continue;
            for (final IRBasicBlock b : f.blocks()) {
                for (final IRInstruction in : b.instructions()) {
                    if (in instanceof IRInstruction.Call c) {
                        callCounts.merge(c.function(), 1, Integer::sum);
                    }
                }
            }
        }

        // Eerst inlinen (kan functies vergroten, daarna per-functie passes).
        final Inliner inliner = new Inliner(calleeMap, callCounts);
        final var inlinedFunctions = module.functions().stream()
                .map(f -> {
                    if (f.isExternal()) return f;
                    inliner.run(f);
                    return f;
                })
                .toList();
        final var moduleAfterInline = module.withFunctions(inlinedFunctions);

        final var optimizedFunctions = moduleAfterInline.functions().stream()
                .map(Optimizer::optimize)
                .toList();
        // Interprocedurale nullness (single-module).
        for (final IRFunction f : optimizedFunctions) {
            if (f.isExternal()) continue;
            try {
                final var res = io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowEngine.analyze(
                        f, new io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.NullnessAnalysis());
                io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Nullness rn =
                        io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Nullness.UNKNOWN;
                boolean any = false;
                for (final var b : f.blocks()) {
                    for (final var instr : b.instructions()) {
                        if (instr instanceof io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction.Return r
                                && r.value() != null) {
                            final String vname = (r.value() instanceof io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp t)
                                    ? t.name()
                                    : (r.value() instanceof io.github.potjerodekool.nabu.backend.ir.values.IRValue.Named n) ? n.name() : null;
                            if (vname != null) {
                                final var m2 = res.in(b.label());
                                if (m2 != null) {
                                    final var nv = m2.get(vname);
                                    if (nv != null) {
                                        if (!any) { rn = nv; any = true; }
                                        else if (rn != nv) rn = io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Nullness.UNKNOWN;
                                    }
                                }
                            }
                        }
                    }
                }
                io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.NullnessAnalysis.setReturnNullness(f.name, rn);
            } catch (Exception ignored) {}
        }
        return moduleAfterInline.withFunctions(optimizedFunctions);
    }

    public static IRFunction optimize(IRFunction irFunction) {
        // Invalideer dataflow-cache: de IR verandert door optimalisatie.
        io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowCache.invalidate(irFunction);
        // Stap 0: TypeConsistency moet altijd draaien, ook voor functies met Phis,
        // omdat type-conflicten in Phis leiden tot ongeldige stack-map frames.
        irFunction = io.github.potjerodekool.nabu.backend.ir.optimize.TypeConsistency.run(irFunction);

        // Stap 0b: Elimineer phi's toegevoegd door TypeConsistency
        io.github.potjerodekool.nabu.backend.ir.PhiElimination.run(irFunction);

        // Conservatieve guard: functies met Phi-instructies (SSA-joins door
        // if/loops/ternary/try-catch) worden NIET verder geoptimaliseerd. De eerste
        // versies van ConstantFolder/CopyPropagation/GVN/DCE zijn onzuiter in
        // combinatie met Phi's (dode register-referenties: 'Onbekend register',
        // 'Expected I, but found .'). Pass-by-pass vekken in de pipeline loop
        // is het onderzoekaandachtsveld (BISECT).
        // Stap 1: Verwijder onnodige branches (bestaande logica)
        irFunction = removeRedundantBranches(irFunction);

        // Stap 1b: Elimineer onbereikbare blokken op basis van SCCP.
        irFunction = removeUnreachableBlocks(irFunction);

        // Stap 2: alle passes draaien nu op alle functies. GVN is per-blok en
        // slaat Phis over (eigen value number), dus Phi-veilig in deze vorm.
        new io.github.potjerodekool.nabu.backend.ir.optimize.ConstantFolder().run(irFunction);
        new io.github.potjerodekool.nabu.backend.ir.optimize.CopyPropagation().run(irFunction);
        new io.github.potjerodekool.nabu.backend.ir.optimize.GlobalValueNumbering().run(irFunction);
        new io.github.potjerodekool.nabu.backend.ir.optimize.DeadCodeElimination().run(irFunction);
        // Tweede ronde: constant folding kan na GVN nieuwe constanten opleveren.
        new io.github.potjerodekool.nabu.backend.ir.optimize.ConstantFolder().run(irFunction);
        new io.github.potjerodekool.nabu.backend.ir.optimize.DeadCodeElimination().run(irFunction);

        return irFunction;
    }

    /**
     * Verwijder branches die naar het direct volgende blok wijzen (redundante
     * goto) door dat blok in de voorgaande te <i>vouwen</i>.
     *
     * <p>Alleen de branch schrappen is onvoldoende: het blok houdt dan geen
     * terminator en de LLVM-verifier weigert de module ("Basic Block in
     * function ... does not have terminator"). De opvolgende instructies
     * moeten dus in hetzelfde blok terechtkomen.
     *
     * <p>Het vouwen is alleen veilig als het doelblok precies één predecessor
     * heeft (anders verliezen andere takken hun sprongdoel). Het doelblok
     * verdwijnt daardoor als label; de nieuwe label van de {@code br} in het
     * bronblok wordt daarom met dezelfde regel meegevouwen.
     */
    private static IRFunction removeRedundantBranches(IRFunction irFunction) {
        final var original = irFunction.blocks();
        final var predecessors = countPredecessors(original);

        final var newBlocks = new ArrayList<IRBasicBlock>();
        int i = 0;
        while (i < original.size()) {
            final var block = original.get(i);
            final var merged = new IRBasicBlock(block.label());

            for (final var instr : block.instructions()) {
                merged.add(instr);
            }

            // Volg zolang de laatste instructie een branch is naar het blok
            // daadirect erop, en dat blok geen andere predecessors heeft.
            while (i + 1 < original.size()) {
                final var next = original.get(i + 1);
                if (!branchesTo(merged.getTerminator(), next.label())
                        || predecessors.getOrDefault(next.label(), 0) != 1) {
                    break;
                }

                // De overbodige branch zelf niet overnemen; de instructies erachter wel.
                final var nextInstructions = new ArrayList<>(next.instructions());
                nextInstructions.remove(merged.getTerminator());
                for (final var instr : nextInstructions) {
                    merged.add(instr);
                }
                i++;
            }

            newBlocks.add(merged);
            i++;
        }

        return irFunction.withBlocks(newBlocks);
    }

    /** Elimineert onbereikbare basic blocks op basis van SCCP. */
    private static IRFunction removeUnreachableBlocks(IRFunction irFunction) {
        if (irFunction.isExternal() || irFunction.blocks().isEmpty()) return irFunction;
        final var sccp = io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.SparseConditionalConstantPropagation.analyze(irFunction);
        final var original = irFunction.blocks();
        final var newBlocks = new ArrayList<IRBasicBlock>();
        boolean changed = false;
        for (final var block : original) {
            if (sccp.isExecutable(block.label())) {
                newBlocks.add(block);
            } else {
                changed = true;
            }
        }
        if (!changed) return irFunction;
        return irFunction.withBlocks(newBlocks);
    }

    /** Aantal keer dat elk blok-label als sprongdoel wordt genoemd. */
    private static Map<String, Integer> countPredecessors(final List<IRBasicBlock> blocks) {
        final Map<String, Integer> counts = new HashMap<>();
        for (final var block : blocks) {
            for (final var successor : block.getSuccessors().values()) {
                if (successor != null) {
                    counts.merge(successor, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static boolean branchesTo(final IRInstruction terminator, final String label) {
        return terminator instanceof IRInstruction.Branch branch
                && branch.targetLabel().equals(label);
    }
}

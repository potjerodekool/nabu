package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;

import java.util.*;

/**
 * Dominator-boom berekening voor een control flow graph.
 *
 * Gebruikt het iteratieve algoritme om de dominator-relatie te berekenen.
 * Levert:
 * - Onmiddellijke dominator (idom) per blok
 * - Dominance frontiers per blok
 * - Preorder-traversale van de dominator-boom
 */
public class Dominators {

    private final Map<String, String> idom;           // blok → onmiddellijke dominator
    private final Map<String, List<String>> domTree;  // blok → kinderen in dom-tree
    private final Map<String, Set<String>> domFrontiers; // blok → dominance frontier
    private final List<String> preorder;              // dominator-tree preorder

    private Dominators(Map<String, String> idom,
                       Map<String, List<String>> domTree,
                       Map<String, Set<String>> domFrontiers,
                       List<String> preorder) {
        this.idom = idom;
        this.domTree = domTree;
        this.domFrontiers = domFrontiers;
        this.preorder = preorder;
    }

    /**
     * Bereken dominators voor een functie.
     */
    public static Dominators compute(IRFunction function) {
        final var blocks = function.blocks();
        if (blocks.isEmpty()) {
            return new Dominators(Map.of(), Map.of(), Map.of(), List.of());
        }

        final var entryLabel = function.entryBlock().label();
        final var predecessors = computePredecessors(blocks);
        final var blockLabels = blocks.stream()
                .map(IRBasicBlock::label)
                .toList();

        // Stap 1: bereken idom via iteratief algoritme
        final var idom = computeIdom(blockLabels, entryLabel, predecessors);

        // Stap 2: bouw dominator-boom (ouder → kinderen)
        final var domTree = buildDomTree(idom, blockLabels);

        // Stap 3: bereken dominance frontiers
        final var domFrontiers = computeDomFrontiers(blockLabels, idom, predecessors);

        // Stap 4: bereken preorder-traversale
        final var preorder = computePreorder(entryLabel, domTree);

        return new Dominators(idom, domTree, domFrontiers, preorder);
    }

    /**
     * Geeft de onmiddellijke dominator van een blok terug.
     * @return het label van de idom, of null voor het entry-blok.
     */
    public String getIdom(String label) {
        return idom.get(label);
    }

    /**
     * Controleert of blok a blok b domineert.
     */
    public boolean dominates(String a, String b) {
        if (a.equals(b)) return true;
        var current = idom.get(b);
        while (current != null) {
            if (current.equals(a)) return true;
            current = idom.get(current);
        }
        return false;
    }

    /**
     * Geeft het dominance frontier van een blok terug.
     */
    public Set<String> getDominanceFrontier(String label) {
        return domFrontiers.getOrDefault(label, Set.of());
    }

    /**
     * Geeft de blok-labels in dominator-tree preorder.
     */
    public List<String> getPreorder() {
        return Collections.unmodifiableList(preorder);
    }

    /**
     * Geeft de kinderen van een blok in de dominator-boom.
     */
    public List<String> getChildren(String label) {
        return domTree.getOrDefault(label, List.of());
    }

    // -------------------------------------------------------
    // Intern: predecessor berekening
    // -------------------------------------------------------

    public static Map<String, Set<String>> computePredecessors(List<IRBasicBlock> blocks) {
        final Map<String, Set<String>> preds = new LinkedHashMap<>();
        for (var block : blocks) {
            preds.put(block.label(), new LinkedHashSet<>());
        }

        for (var block : blocks) {
            final var terminator = block.getTerminator();
            if (terminator instanceof IRInstruction.Branch branch) {
                preds.computeIfAbsent(branch.targetLabel(), k -> new LinkedHashSet<>())
                        .add(block.label());
            } else if (terminator instanceof IRInstruction.CondBranch condBranch) {
                preds.computeIfAbsent(condBranch.trueLabel(), k -> new LinkedHashSet<>())
                        .add(block.label());
                preds.computeIfAbsent(condBranch.falseLabel(), k -> new LinkedHashSet<>())
                        .add(block.label());
            }
        }

        return preds;
    }

    // -------------------------------------------------------
    // Intern: idom berekening (iteratief algoritme)
    // -------------------------------------------------------

    private static Map<String, String> computeIdom(List<String> blockLabels,
                                                    String entryLabel,
                                                    Map<String, Set<String>> predecessors) {
        if (blockLabels.size() <= 1) {
            return Map.of();
        }

        // Reverse postorder
        final var rpo = reversePostorder(blockLabels, entryLabel, predecessors);
        final var rpoIndex = new HashMap<String, Integer>();
        for (int i = 0; i < rpo.size(); i++) {
            rpoIndex.put(rpo.get(i), i);
        }

        final var idom = new HashMap<String, String>();
        idom.put(entryLabel, entryLabel);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < rpo.size(); i++) {
                final var b = rpo.get(i);
                final var preds = predecessors.getOrDefault(b, Set.of());
                if (preds.isEmpty()) continue;

                // Zoek eerste predecessor met gekend idom
                String newIdom = null;
                for (var p : preds) {
                    if (idom.containsKey(p)) {
                        newIdom = p;
                        break;
                    }
                }
                if (newIdom == null) continue;

                // Intersect met overige predecessors
                for (var p : preds) {
                    if (!idom.containsKey(p)) continue;
                    if (p.equals(newIdom)) continue;
                    newIdom = intersect(newIdom, p, idom, rpoIndex);
                }

                if (!newIdom.equals(idom.get(b))) {
                    idom.put(b, newIdom);
                    changed = true;
                }
            }
        }

        // Verwijder self-reference voor entry
        idom.remove(entryLabel);
        return idom;
    }

    private static String intersect(String b1, String b2,
                                     Map<String, String> idom,
                                     Map<String, Integer> rpoIndex) {
        var finger1 = b1;
        var finger2 = b2;
        while (!finger1.equals(finger2)) {
            while (rpoIndex.getOrDefault(finger1, 0) > rpoIndex.getOrDefault(finger2, 0)) {
                finger1 = idom.getOrDefault(finger1, finger1);
            }
            while (rpoIndex.getOrDefault(finger2, 0) > rpoIndex.getOrDefault(finger1, 0)) {
                finger2 = idom.getOrDefault(finger2, finger2);
            }
        }
        return finger1;
    }

    // -------------------------------------------------------
    // Intern: reverse postorder
    // -------------------------------------------------------

    private static List<String> reversePostorder(List<String> blockLabels,
                                                  String entryLabel,
                                                  Map<String, Set<String>> predecessors) {
        // Eerst postorder via DFS vanaf entry
        final var visited = new LinkedHashSet<String>();
        final var postorder = new ArrayList<String>();

        // Bouw adjacency list (block → successors)
        final var successors = new HashMap<String, Set<String>>();
        for (var label : blockLabels) {
            successors.put(label, new LinkedHashSet<>());
        }
        for (var block : blockLabels) {
            for (var pred : predecessors.getOrDefault(block, Set.of())) {
                successors.computeIfAbsent(pred, k -> new LinkedHashSet<>()).add(block);
            }
        }

        dfs(entryLabel, successors, visited, postorder);

        // Voeg eventuele ontoegankelijke blokken toe
        for (var label : blockLabels) {
            if (!visited.contains(label)) {
                postorder.add(label);
            }
        }

        // Keer om voor reverse postorder
        Collections.reverse(postorder);
        return postorder;
    }

    private static void dfs(String label, Map<String, Set<String>> successors,
                             Set<String> visited, List<String> postorder) {
        if (!visited.add(label)) return;
        for (var succ : successors.getOrDefault(label, Set.of())) {
            dfs(succ, successors, visited, postorder);
        }
        postorder.add(label);
    }

    // -------------------------------------------------------
    // Intern: dominator boom opbouwen
    // -------------------------------------------------------

    private static Map<String, List<String>> buildDomTree(Map<String, String> idom,
                                                           List<String> blockLabels) {
        final var tree = new LinkedHashMap<String, List<String>>();
        for (var label : blockLabels) {
            tree.put(label, new ArrayList<>());
        }
        for (var entry : idom.entrySet()) {
            final var parent = entry.getValue();
            final var child = entry.getKey();
            tree.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
        }
        return tree;
    }

    // -------------------------------------------------------
    // Intern: preorder traversale
    // -------------------------------------------------------

    private static List<String> computePreorder(String entryLabel,
                                                 Map<String, List<String>> domTree) {
        final var result = new ArrayList<String>();
        preorderTraversal(entryLabel, domTree, result);
        return result;
    }

    private static void preorderTraversal(String label, Map<String, List<String>> domTree,
                                           List<String> result) {
        result.add(label);
        for (var child : domTree.getOrDefault(label, List.of())) {
            preorderTraversal(child, domTree, result);
        }
    }

    // -------------------------------------------------------
    // Intern: dominance frontiers
    // -------------------------------------------------------

    private static Map<String, Set<String>> computeDomFrontiers(
            List<String> blockLabels,
            Map<String, String> idom,
            Map<String, Set<String>> predecessors) {

        final var df = new LinkedHashMap<String, Set<String>>();
        for (var label : blockLabels) {
            df.put(label, new LinkedHashSet<>());
        }

        for (var b : blockLabels) {
            final var preds = predecessors.getOrDefault(b, Set.of());
            if (preds.size() < 2) continue;

            for (var p : preds) {
                var runner = p;
                final var idomB = idom.get(b);
                while (runner != null && !runner.equals(idomB)) {
                    df.computeIfAbsent(runner, k -> new LinkedHashSet<>()).add(b);
                    runner = idom.get(runner);
                }
            }
        }

        return df;
    }
}

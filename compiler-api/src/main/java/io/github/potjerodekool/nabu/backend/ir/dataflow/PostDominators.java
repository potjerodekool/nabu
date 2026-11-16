package io.github.potjerodekool.nabu.backend.ir.dataflow;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;

import java.util.*;

/**
 * Post-dominator-analyse: onmiddellijke post-dominator (ipdom),
 * post-dominator-boom, post-dominance frontiers en preorder.
 *
 * <p>Gebruikt een virtuele exit-node die vanuit alle exit-blokken bereikbaar is.
 */
public class PostDominators {

    private static final String VIRTUAL_EXIT = "__EXIT__";

    private final Map<String, String> ipdom;          // blok → ipdom
    private final Map<String, List<String>> tree;     // blok → kinderen
    private final Map<String, Set<String>> frontiers; // blok → pdf
    private final List<String> preorder;

    private PostDominators(Map<String, String> ipdom,
                           Map<String, List<String>> tree,
                           Map<String, Set<String>> frontiers,
                           List<String> preorder) {
        this.ipdom = ipdom;
        this.tree = tree;
        this.frontiers = frontiers;
        this.preorder = preorder;
    }

    public static PostDominators compute(IRFunction function) {
        final var blocks = function.blocks();
        if (blocks.isEmpty()) {
            return new PostDominators(Map.of(), Map.of(), Map.of(), List.of());
        }

        final var labels = blocks.stream().map(IRBasicBlock::label).toList();
        final var succs = buildSuccessors(blocks);

        // Preds in original = succs in reverse. We'll compute idom on reverse graph
        // with VIRTUAL_EXIT as entry, where VIRTUAL_EXIT's successors are the exit blocks? 
        // Reverse graph: edge B->S becomes S->B. Entry of reverse = VIRTUAL_EXIT,
        // predecessors of VIRTUAL_EXIT in reverse = exit blocks (blocks with no succs).
        // Actually: in reverse graph, to find post-dominators, we want dominators
        // in the graph where edges are reversed and a single exit. Standard: 
        // add virtual exit node X; for each exit block E, edge E->X. Then reverse
        // all edges. Compute dominators with X as entry. ipdom(b) = idom in reversed
        // graph (excluding X).

        // Build reverse adjacency: for each node, its predecessors in original become
        // successors in reverse. Plus virtual exit.
        final Map<String, Set<String>> revSuccs = new LinkedHashMap<>();
        for (var l : labels) revSuccs.put(l, new LinkedHashSet<>());
        revSuccs.put(VIRTUAL_EXIT, new LinkedHashSet<>());

        for (var b : blocks) {
            final var ss = succs.getOrDefault(b.label(), Set.of());
            if (ss.isEmpty()) {
                // exit block -> virtual exit in original; in reverse: virtual exit -> block
                revSuccs.get(VIRTUAL_EXIT).add(b.label());
            } else {
                for (var s : ss) {
                    // original edge b->s ; reverse edge s->b
                    revSuccs.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(b.label());
                }
            }
        }

        // Now compute idom on reverse graph with entry VIRTUAL_EXIT.
        // We need a generic idom computation on adjacency (successors).
        final Map<String, String> idom = computeIdomOnGraph(
                new ArrayList<>(revSuccs.keySet()), VIRTUAL_EXIT, revSuccs);
        // For a block whose idom is virtual exit, ipdom is null (no post-dominator) except exit blocks post-dom by virtual exit.
        final Map<String, String> ipdomMap = new LinkedHashMap<>();
        final Map<String, List<String>> treeMap = new LinkedHashMap<>();
        for (var l : labels) {
            treeMap.put(l, new ArrayList<>());
        }
        for (var entry : idom.entrySet()) {
            final var child = entry.getKey();
            if (child.equals(VIRTUAL_EXIT)) continue;
            final var parent = entry.getValue();
            if (parent.equals(VIRTUAL_EXIT)) {
                ipdomMap.put(child, null);
            } else {
                ipdomMap.put(child, parent);
            }
            if (!parent.equals(VIRTUAL_EXIT)) {
                treeMap.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
            }
        }
        // Ensure all labels present
        for (var l : labels) {
            ipdomMap.putIfAbsent(l, null);
            treeMap.putIfAbsent(l, new ArrayList<>());
        }

        // Frontiers on reverse graph (post-dominance frontiers) using original preds? 
        // PDF(b) = { x | x has successor y such that b post-dominates y but not strictly? } 
        // Use standard formula on reverse graph: frontier in dominator sense on rev graph.
        final Map<String, Set<String>> pdf = computeFrontiersOnGraph(labels, idom, revSuccs, VIRTUAL_EXIT);

        // Preorder of post-dom tree (root = virtual exit's children). Traverse from each child of virtual exit.
        final List<String> preorderList = new ArrayList<>();
        final var roots = new ArrayList<String>();
        for (var l : labels) {
            final var p = ipdomMap.get(l);
            if (p == null) roots.add(l); // blocks whose ipdom is null = connected to virtual exit
        }
        // Actually roots are children of virtual exit in treeMap? We didn't add them. Let's traverse from virtual exit's children.
        final List<String> virtualChildren = new ArrayList<>();
        for (var l : labels) {
            if (Objects.equals(ipdomMap.get(l), null) && idom.getOrDefault(l, "").equals(VIRTUAL_EXIT)) {
                virtualChildren.add(l);
            }
        }
        // preorder: virtual exit not included; traverse children
        for (var root : virtualChildren) {
            preorderTraversal(root, treeMap, preorderList);
        }
        // add any missing labels
        for (var l : labels) {
            if (!preorderList.contains(l)) preorderList.add(l);
        }

        return new PostDominators(ipdomMap, treeMap, pdf, preorderList);
    }

    private static Map<String, Set<String>> buildSuccessors(List<IRBasicBlock> blocks) {
        final Map<String, Set<String>> succs = new LinkedHashMap<>();
        for (var b : blocks) succs.put(b.label(), new LinkedHashSet<>());
        for (var b : blocks) {
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

    // Generic idom on a graph given successors adjacency.
    private static Map<String, String> computeIdomOnGraph(List<String> nodeLabels,
                                                          String entryLabel,
                                                          Map<String, Set<String>> successors) {
        // reverse postorder via dfs from entry on successors
        final var visited = new LinkedHashSet<String>();
        final var postorder = new ArrayList<String>();
        dfs(entryLabel, successors, visited, postorder);
        for (var l : nodeLabels) {
            if (!visited.contains(l)) postorder.add(l);
        }
        Collections.reverse(postorder); // rpo
        final var rpo = postorder;
        final var rpoIndex = new HashMap<String, Integer>();
        for (int i = 0; i < rpo.size(); i++) rpoIndex.put(rpo.get(i), i);

        // predecessors in this graph = reverse of successors
        final Map<String, Set<String>> preds = new LinkedHashMap<>();
        for (var l : nodeLabels) preds.put(l, new LinkedHashSet<>());
        for (var l : nodeLabels) {
            for (var s : successors.getOrDefault(l, Set.of())) {
                preds.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(l);
            }
        }

        final var idom = new HashMap<String, String>();
        idom.put(entryLabel, entryLabel);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < rpo.size(); i++) {
                final var b = rpo.get(i);
                final var ps = preds.getOrDefault(b, Set.of());
                if (ps.isEmpty()) continue;
                String newIdom = null;
                for (var p : ps) {
                    if (idom.containsKey(p)) { newIdom = p; break; }
                }
                if (newIdom == null) continue;
                for (var p : ps) {
                    if (!idom.containsKey(p)) continue;
                    if (p.equals(newIdom)) continue;
                    newIdom = intersectGeneric(newIdom, p, idom, rpoIndex);
                }
                if (!newIdom.equals(idom.get(b))) {
                    idom.put(b, newIdom);
                    changed = true;
                }
            }
        }
        idom.remove(entryLabel);
        return idom;
    }

    private static String intersectGeneric(String b1, String b2, Map<String, String> idom,
                                          Map<String, Integer> rpoIndex) {
        var f1 = b1; var f2 = b2;
        while (!f1.equals(f2)) {
            while (rpoIndex.getOrDefault(f1, 0) > rpoIndex.getOrDefault(f2, 0)) {
                f1 = idom.getOrDefault(f1, f1);
            }
            while (rpoIndex.getOrDefault(f2, 0) > rpoIndex.getOrDefault(f1, 0)) {
                f2 = idom.getOrDefault(f2, f2);
            }
        }
        return f1;
    }

    private static void dfs(String label, Map<String, Set<String>> successors,
                            Set<String> visited, List<String> postorder) {
        if (!visited.add(label)) return;
        for (var s : successors.getOrDefault(label, Set.of())) dfs(s, successors, visited, postorder);
        postorder.add(label);
    }

    private static Map<String, Set<String>> computeFrontiersOnGraph(List<String> labels,
                                                                    Map<String, String> idom,
                                                                    Map<String, Set<String>> successors,
                                                                    String entryLabel) {
        // predecessors in original graph? For frontier formula we need predecessors in THIS graph.
        final Map<String, Set<String>> preds = new LinkedHashMap<>();
        for (var l : labels) preds.put(l, new LinkedHashSet<>());
        preds.put(entryLabel, new LinkedHashSet<>());
        for (var l : successors.keySet()) {
            for (var s : successors.getOrDefault(l, Set.of())) {
                preds.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(l);
            }
        }
        final var allNodes = new ArrayList<>(successors.keySet());

        final Map<String, Set<String>> df = new LinkedHashMap<>();
        for (var l : allNodes) df.put(l, new LinkedHashSet<>());

        for (var b : allNodes) {
            if (b.equals(entryLabel)) continue;
            if (!idom.containsKey(b) && !b.equals(entryLabel)) {
                // unreachable maybe
            }
            final var ps = preds.getOrDefault(b, Set.of());
            if (ps.size() < 2) {
                // still compute? standard requires runner walk; handle generally
            }
            final String idomB = idom.get(b);
            for (var p : ps) {
                var runner = p;
                while (runner != null && !runner.equals(idomB) && !runner.equals(entryLabel)) {
                    // careful: entry's idom is itself
                    df.computeIfAbsent(runner, k -> new LinkedHashSet<>()).add(b);
                    String next = idom.get(runner);
                    if (next == null) next = entryLabel;
                    if (runner.equals(next)) break;
                    runner = next;
                }
                // if runner == idomB stop; if reached entry handle
            }
        }
        // Remove virtual exit from result keys
        df.remove(VIRTUAL_EXIT);
        // Remove virtual exit from sets
        for (var s : df.values()) s.remove(VIRTUAL_EXIT);
        // Keep only original labels
        final Map<String, Set<String>> result = new LinkedHashMap<>();
        for (var l : labels) result.put(l, df.getOrDefault(l, new LinkedHashSet<>()));
        return result;
    }

    private static void preorderTraversal(String label, Map<String, List<String>> tree,
                                          List<String> result) {
        result.add(label);
        for (var c : tree.getOrDefault(label, List.of())) preorderTraversal(c, tree, result);
    }

    public String getIPostDom(String label) { return ipdom.get(label); }

    public List<String> getChildren(String label) {
        return tree.getOrDefault(label, List.of());
    }

    public Set<String> getFrontier(String label) {
        return frontiers.getOrDefault(label, Set.of());
    }

    public List<String> getPreorder() {
        return Collections.unmodifiableList(preorder);
    }

    public boolean postDominates(String a, String b) {
        // a post-dominates b iff a is on every path from b to exit
        // equivalently in reverse graph a dominates b
        if (a.equals(b)) return true;
        var cur = ipdom.get(b);
        while (cur != null) {
            if (cur.equals(a)) return true;
            cur = ipdom.get(cur);
        }
        return false;
    }
}
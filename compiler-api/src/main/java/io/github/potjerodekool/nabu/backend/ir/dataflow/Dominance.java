package io.github.potjerodekool.nabu.backend.ir.dataflow;

import io.github.potjerodekool.nabu.backend.ir.Dominators;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;

import java.util.*;

/**
 * Dominance-analyse: onmiddellijke dominator, dominator-boom,
 * dominance frontiers en post-dominators.
 */
public final class Dominance {

    private final IRFunction function;
    private final Dominators dominators;
    private final PostDominators postDominators;

    private Dominance(IRFunction function, Dominators dominators,
                      PostDominators postDominators) {
        this.function = function;
        this.dominators = dominators;
        this.postDominators = postDominators;
    }

    public static Dominance analyze(IRFunction function) {
        return new Dominance(function,
                Dominators.compute(function),
                PostDominators.compute(function));
    }

    public static Dominance analyzeForPhiPlacement(IRFunction function) {
        return new Dominance(function,
                Dominators.computeForPhiPlacement(function),
                PostDominators.compute(function));
    }

    public Dominators dominators() { return dominators; }
    public PostDominators postDominators() { return postDominators; }

    public boolean dominates(String a, String b) { return dominators.dominates(a, b); }
    public boolean postDominates(String a, String b) { return postDominators.postDominates(a, b); }

    public String idom(String label) { return dominators.getIdom(label); }
    public String ipdom(String label) { return postDominators.getIPostDom(label); }

    public List<String> preorder() { return dominators.getPreorder(); }
    public List<String> postPreorder() { return postDominators.getPreorder(); }

    public Set<String> dominanceFrontier(String label) { return dominators.getDominanceFrontier(label); }

    public List<String> children(String label) { return dominators.getChildren(label); }
    public List<String> postChildren(String label) { return postDominators.getChildren(label); }

    /**
     * Dominance frontier van de post-dominator-boom.
     */
    public Set<String> postDominanceFrontier(String label) {
        return postDominators.getFrontier(label);
    }

    public IRFunction function() { return function; }
}
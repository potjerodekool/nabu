package io.github.potjerodekool.nabu.compiler.backend.graph;

import java.util.Collections;
import java.util.List;

import static io.github.potjerodekool.nabu.compiler.CollectionUtils.headAndTailList;
import static io.github.potjerodekool.nabu.compiler.CollectionUtils.tailOf;

public final class Node {
    private final Graph mygraph;
    private final int key;
    private List<Node> succs;
    private List<Node> preds;

    public Node(final Graph g) {
        mygraph = g;
        key = g.incrementNodeCount();
        final List<Node> p = Collections.singletonList(this);
        if (g.getMylast().isEmpty()) {
            g.setMylast(p);
            g.setNodes(g.getMylast());
        } else {
            g.appendNodes(p);
            g.setMylast(p);
        }
    }

    public int getKey() {
        return key;
    }

    List<Node> succ() {
        if (succs == null) {
            return Collections.emptyList();
        }
        return succs;
    }

    void succ(final List<Node> succs) {
        this.succs = succs;
    }

    public List<Node> pred() {
        if (preds == null) {
            return Collections.emptyList();
        }
        return preds;
    }

    void pred(final List<Node> preds) {
        if (preds == null) {
            throw new NullPointerException("preds");
        }
        this.preds = preds;
    }

    private List<Node> cat(final List<Node> a, final List<Node> b) {
        if (a.isEmpty()) {
            return b;
        } else {
            return headAndTailList(a.getFirst(), cat(tailOf(a), b));
        }
    }

    public List<Node> adj() {
        return cat(succ(), pred());
    }

    private int len(final List<Node> l) {
        return l.size();
    }

    private int inDegree() {
        return len(pred());
    }

    private int outDegree() {
        return len(succ());
    }

    public int degree() {
        return inDegree() + outDegree();
    }

    boolean goesTo(final Node node) {
        return succ().contains(node);
    }

    private boolean comesFrom(final Node node) {
        return pred().contains(node);
    }

    public boolean adj(final Node n) {
        return goesTo(n) || comesFrom(n);
    }

    Graph getMygraph() {
        return mygraph;
    }

    @Override
    public boolean equals(final Object obj) {
        return obj == this;
    }

    @Override
    public int hashCode() {
        return key;
    }

}


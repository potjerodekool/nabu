package io.github.potjerodekool.nabu.compiler.backend.graph;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static io.github.potjerodekool.nabu.compiler.util.CollectionUtils.*;

public abstract class Graph {

    private int nodecount = 0;
    private List<Node> nodes = Collections.emptyList();
    private List<Node> mylast = Collections.emptyList();

    int incrementNodeCount() {
        return nodecount++;
    }

    public List<Node> getMylast() {
        return mylast;
    }

    void setMylast(final List<Node> mylast) {
        this.mylast = mylast;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public void appendNodes(final List<Node> list) {
        nodes = concat(nodes, list);
    }

    Node newNode() {
        return new Node(this);
    }

    private void check(final Node n) {
        Objects.requireNonNull(n);
        if (n.getMygraph() != this) {
            throw new IllegalArgumentException("Graph.addEdge using nodes from the wrong graph");
        }
    }

    void addEdge(final Node from, final Node to) {
        if (to == null) {
            return;
        }

        check(from);
        check(to);
        if (from.goesTo(to)) {
            return;
        }
        to.pred(headAndTailList(from, to.pred()));
        from.succ(headAndTailList(to, from.succ()));
    }

    private List<Node> delete(final Node a, final List<Node> l) {
        if (l.isEmpty()) {
            return l;
        } else if (a == l.getFirst()) {
            return tailOf(l);
        } else {
            return headAndTailList(l.getFirst(), delete(a, tailOf(l)));
        }
    }

    public void setNodes(final List<Node> nodes) {
        this.nodes = nodes;
    }
}
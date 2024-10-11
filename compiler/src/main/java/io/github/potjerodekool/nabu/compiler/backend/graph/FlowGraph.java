package io.github.potjerodekool.nabu.compiler.backend.graph;

import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.HashMap;
import java.util.Map;

public abstract class FlowGraph<E> extends Graph {

    final Map<Node, E> map;
    final Map<E, Node> revMap;

    FlowGraph() {
        map = new HashMap<>();
        revMap = new HashMap<>();
    }

    public Map<E, Node> getRevMap() {
        return revMap;
    }

    public Map<Node, E> getMap() {
        return map;
    }

    public final Node toNode(final E e) {
        return revMap.get(e);
    }

    public final E fromNode(final Node node) {
        return map.get(node);
    }

    /**
     * The setOf of temporaries defined by this instruction or block
     */
    public abstract Temp[] def(Node node);

    /**
     * The setOf of temporaries used by this instruction or block
     */
    public abstract Temp[] use(Node node);

    public abstract boolean isMove(Node node);

}

package io.github.potjerodekool.nabu.compiler.backend.graph;

import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

abstract class InterferenceGraph extends Graph {

    public abstract Node node(Temp temp);

    public abstract Temp temp(Node node);
}

package io.github.potjerodekool.nabu.compiler.backend.graph;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

public class IRFlowGraph extends FlowGraph<IStatement> {

    @Override
    public Temp[] def(final Node node) {
        return new Temp[0];
    }

    @Override
    public Temp[] use(final Node node) {
        return new Temp[0];
    }

    @Override
    public boolean isMove(final Node node) {
        return false;
    }
}

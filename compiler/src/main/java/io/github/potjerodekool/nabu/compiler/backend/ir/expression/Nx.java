package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

public class Nx implements Exp {

    private final IStatement stm;

    public Nx(final IStatement stm) {
        this.stm = stm;
    }

    @Override
    public IExpression unEx() {
        return null;
    }

    @Override
    public IStatement unNx() {
        return stm;
    }

    @Override
    public IStatement unCx(final ILabel t, final ILabel f) {
        return null;
    }
}

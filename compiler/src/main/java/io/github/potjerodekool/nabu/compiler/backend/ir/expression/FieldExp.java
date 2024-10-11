package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

public class FieldExp implements Exp {

    private final String name;

    public FieldExp(final String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public IExpression unEx() {
        return null;
    }

    @Override
    public IStatement unNx() {
        return null;
    }

    @Override
    public IStatement unCx(final ILabel t, final ILabel f) {
        return null;
    }
}

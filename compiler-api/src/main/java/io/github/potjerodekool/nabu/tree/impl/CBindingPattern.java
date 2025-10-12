package io.github.potjerodekool.nabu.tree.impl;


import io.github.potjerodekool.nabu.tree.BindingPattern;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

public class CBindingPattern extends CPattern implements BindingPattern {

    private final VariableDeclaratorTree variableDeclarator;

    public CBindingPattern(final VariableDeclaratorTree variableDeclarator,
                           final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
        this.variableDeclarator = variableDeclarator;
    }

    @Override
    public VariableDeclaratorTree getVariableDeclarator() {
        return variableDeclarator;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitBindingPattern(this, param);
    }
}

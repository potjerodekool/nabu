package io.github.potjerodekool.nabu.tree.impl;


import io.github.potjerodekool.nabu.tree.TypePattern;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

/**
 * Implementation of type pattern.
 */
public final class CTypePattern extends CPattern implements TypePattern {

    private final VariableDeclaratorTree variableDeclarator;

    public CTypePattern(final VariableDeclaratorTree variableDeclarator,
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
        return visitor.visitTypePattern(this, param);
    }
}

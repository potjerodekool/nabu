package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class IdentifierTree extends ExpressionTree implements Identifier {

    private final String name;

    public IdentifierTree(final String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitIdentifier(this, param);
    }

    @Override
    public String toString() {
        return name;
    }
}

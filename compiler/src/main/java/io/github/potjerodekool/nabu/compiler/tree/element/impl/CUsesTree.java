package io.github.potjerodekool.nabu.compiler.tree.element.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.UsesTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class CUsesTree extends CDirective implements UsesTree {

    private final ExpressionTree serviceName;

    public CUsesTree(final ExpressionTree serviceName,
                     final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
        this.serviceName = serviceName;
    }

    @Override
    public ExpressionTree getServiceName() {
        return serviceName;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitUses(this, param);
    }
}

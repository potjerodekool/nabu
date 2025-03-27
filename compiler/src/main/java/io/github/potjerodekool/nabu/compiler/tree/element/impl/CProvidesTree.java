package io.github.potjerodekool.nabu.compiler.tree.element.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.ProvidesTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

import java.util.ArrayList;
import java.util.List;

public class CProvidesTree extends CDirective implements ProvidesTree {

    private final ExpressionTree serviceName;
    private final List<ExpressionTree> implementations;

    public CProvidesTree(final ExpressionTree serviceName,
                         final List<ExpressionTree> implementations,
                         final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
        this.serviceName = serviceName;
        this.implementations = new ArrayList<>(implementations);
    }

    @Override
    public ExpressionTree getServiceName() {
        return serviceName;
    }

    @Override
    public List<? extends ExpressionTree> getImplementationNames() {
        return implementations;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitProvides(this, param);
    }
}

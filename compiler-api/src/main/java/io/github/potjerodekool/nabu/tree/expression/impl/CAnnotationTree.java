package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;

import java.util.ArrayList;
import java.util.List;

public class CAnnotationTree extends CExpressionTree implements AnnotationTree {

    private final IdentifierTree name;
    private final List<ExpressionTree> arguments = new ArrayList<>();

    public CAnnotationTree(final IdentifierTree name,
                           final List<ExpressionTree> arguments,
                           final int lineNumber,
                           final int columnNumber) {
        super(lineNumber, columnNumber);
        this.name = name;
        this.arguments.addAll(arguments);
    }


    public IdentifierTree getName() {
        return name;
    }

    public List<ExpressionTree> getArguments() {
        return arguments;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitAnnotation(this, param);
    }
}

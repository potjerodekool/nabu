package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.TypeUnionExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.builder.TypeUnionExpressionBuilder;

import java.util.List;

/**
 * Implementation of TypeUnionTree.
 */
public class CTypeUnionTreeExpression extends CExpressionTree implements TypeUnionExpressionTree {

    private final List<ExpressionTree> alternatives;

    public CTypeUnionTreeExpression(final List<ExpressionTree> alternatives,
                                    final int lineNumner,
                                    final int columnNumber) {
        super(lineNumner, columnNumber);
        this.alternatives = List.copyOf(alternatives);
    }

    public CTypeUnionTreeExpression(final TypeUnionExpressionBuilder typeUnionExpressionBuilder) {
        super(typeUnionExpressionBuilder);
        this.alternatives = List.copyOf(typeUnionExpressionBuilder.getAlternatives());
    }

    public List<ExpressionTree> getAlternatives() {
        return alternatives;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeUnion(this, param);
    }
}

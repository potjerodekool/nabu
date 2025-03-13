package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.FieldAccessExpressionBuilder;


public class CFieldAccessExpressionTree extends CExpressionTree implements FieldAccessExpressionTree {

    private ExpressionTree target;

    private ExpressionTree field;

    public CFieldAccessExpressionTree(final ExpressionTree target,
                                      final ExpressionTree field,
                                      final int lineNumber,
                                      final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.target = target;
        this.field = field;
    }

    public CFieldAccessExpressionTree(final FieldAccessExpressionBuilder fieldAccessExpressionBuilder) {
        super(fieldAccessExpressionBuilder);
        this.target = fieldAccessExpressionBuilder.getTarget();
        this.field = fieldAccessExpressionBuilder.getField();
    }

    public ExpressionTree getTarget() {
        return target;
    }

    @Override
    public FieldAccessExpressionTree target(final ExpressionTree target) {
        this.target = target;
        return this;
    }

    @Override
    public ExpressionTree getField() {
        return field;
    }

    public CFieldAccessExpressionTree field(final ExpressionTree field) {
        this.field = field;
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitFieldAccessExpression(this, param);
    }

    @Override
    public FieldAccessExpressionBuilder builder() {
        return new FieldAccessExpressionBuilder(this);
    }

    @Override
    public String toString() {
        if (target == null) {
            return field.toString();
        } else {
            final var targetStr = target.toString();
            final var fieldStr = field.toString();
            return String.format("%s.%s", targetStr, fieldStr);
        }
    }
}

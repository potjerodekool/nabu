package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.ArrayAccessExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CArrayAccessExpression;

public class ArrayAccessExpressionBuilder extends ExpressionBuilder<ArrayAccessExpression> {

    private ExpressionTree expression;
    private ExpressionTree index;

    public ArrayAccessExpressionBuilder() {
        super();
    }

    public ArrayAccessExpressionBuilder(final ArrayAccessExpression original) {
        super(original);
        this.expression = original.getExpression();
        this.index = original.getIndex();
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public ArrayAccessExpressionBuilder expression(final ExpressionTree expressionTree) {
        this.expression = expressionTree;
        return this;
    }

    public ExpressionTree getIndex() {
        return index;
    }

    public ArrayAccessExpressionBuilder index(final ExpressionTree index) {
        this.index = index;
        return this;
    }

    @Override
    public ArrayAccessExpressionBuilder self() {
        return this;
    }

    @Override
    public ArrayAccessExpression build() {
        return new CArrayAccessExpression(this);
    }
}

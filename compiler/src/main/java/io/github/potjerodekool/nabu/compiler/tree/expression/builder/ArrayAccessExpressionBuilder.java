package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.ArrayAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CArrayAccessExpressionTree;

public class ArrayAccessExpressionBuilder extends ExpressionBuilder<ArrayAccessExpressionTree,ArrayAccessExpressionBuilder> {

    private ExpressionTree expression;
    private ExpressionTree index;

    public ArrayAccessExpressionBuilder() {
        super();
    }

    public ArrayAccessExpressionBuilder(final ArrayAccessExpressionTree original) {
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
    public ArrayAccessExpressionTree build() {
        return new CArrayAccessExpressionTree(this);
    }
}

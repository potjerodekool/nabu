package io.github.potjerodekool.nabu.tree.expression;

public interface CastExpressionTree extends ExpressionTree {

    ExpressionTree getExpression();

    ExpressionTree getTargetType();

}

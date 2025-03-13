package io.github.potjerodekool.nabu.compiler.tree.expression;

public interface CastExpressionTree extends ExpressionTree {

    ExpressionTree getExpression();

    ExpressionTree getTargetType();

}

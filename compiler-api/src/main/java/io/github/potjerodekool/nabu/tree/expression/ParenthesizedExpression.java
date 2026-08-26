package io.github.potjerodekool.nabu.tree.expression;

public interface ParenthesizedExpression extends ExpressionTree {

    ExpressionTree getExpression();
}

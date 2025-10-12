package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tag;

public interface UnaryExpressionTree extends ExpressionTree {

    Tag getTag();

    ExpressionTree getExpression();

}

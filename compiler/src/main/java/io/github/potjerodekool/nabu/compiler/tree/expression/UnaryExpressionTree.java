package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.Tag;

public interface UnaryExpressionTree extends ExpressionTree {

    Tag getTag();

    ExpressionTree getExpression();

}

package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.tree.expression.builder.BinaryExpressionBuilder;

public interface BinaryExpressionTree extends ExpressionTree {

    ExpressionTree getLeft();

    ExpressionTree getRight();

    Tag getTag();

    BinaryExpressionBuilder builder();
}

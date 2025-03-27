package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.BinaryExpressionBuilder;

public interface BinaryExpressionTree extends ExpressionTree {

    ExpressionTree getLeft();

    ExpressionTree getRight();

    Tag getTag();

    BinaryExpressionBuilder builder();
}

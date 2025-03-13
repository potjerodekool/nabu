package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public interface UnaryExpressionTree extends ExpressionTree {

    Tag getTag();

    ExpressionTree getExpression();

}

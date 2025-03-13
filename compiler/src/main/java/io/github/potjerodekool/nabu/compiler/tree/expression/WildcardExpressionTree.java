package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.type.BoundKind;

public interface WildcardExpressionTree extends ExpressionTree {

    ExpressionTree getBound();

    BoundKind getBoundKind();

}

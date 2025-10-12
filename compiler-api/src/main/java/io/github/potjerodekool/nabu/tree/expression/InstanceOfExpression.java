package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tree;

public interface InstanceOfExpression extends ExpressionTree {

    ExpressionTree getExpression();

    Tree getTypeExpression();
}

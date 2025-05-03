package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

public interface InstanceOfExpression extends ExpressionTree {

    ExpressionTree getExpression();

    Tree getTypeExpression();
}

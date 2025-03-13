package io.github.potjerodekool.nabu.compiler.tree.expression;

public interface InstanceOfExpression extends ExpressionTree {

    ExpressionTree getExpression();

    ExpressionTree getTypeExpression();
}

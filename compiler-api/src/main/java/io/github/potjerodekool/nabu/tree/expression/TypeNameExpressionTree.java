package io.github.potjerodekool.nabu.tree.expression;

public interface TypeNameExpressionTree extends ExpressionTree {

    ExpressionTree getPackageName();

    ExpressionTree getIdenifier();

}

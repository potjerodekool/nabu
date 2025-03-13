package io.github.potjerodekool.nabu.compiler.tree.expression;

public interface TypeNameExpressionTree extends ExpressionTree {

    ExpressionTree getPackageName();

    ExpressionTree getIdenifier();

}

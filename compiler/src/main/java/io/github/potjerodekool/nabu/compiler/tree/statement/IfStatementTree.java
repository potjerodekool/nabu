package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public interface IfStatementTree extends Statement {


    ExpressionTree getExpression();

    Statement getThenStatement();

    Statement getElseStatement();

    IfStatementTreeBuilder builder();
}

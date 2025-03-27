package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.IfStatementTreeBuilder;

public interface IfStatementTree extends StatementTree {


    ExpressionTree getExpression();

    StatementTree getThenStatement();

    StatementTree getElseStatement();

    IfStatementTreeBuilder builder();
}

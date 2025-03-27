package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.ReturnStatementTreeBuilder;

public interface ReturnStatementTree extends StatementTree {

    ExpressionTree getExpression();

    ReturnStatementTreeBuilder builder();
}

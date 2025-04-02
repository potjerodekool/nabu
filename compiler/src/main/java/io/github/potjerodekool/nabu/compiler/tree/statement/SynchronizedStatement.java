package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public interface SynchronizedStatement extends StatementTree {

    ExpressionTree getExpression();

    BlockStatementTree getBody();
}

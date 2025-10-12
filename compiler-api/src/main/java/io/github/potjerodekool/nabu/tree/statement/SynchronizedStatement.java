package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public interface SynchronizedStatement extends StatementTree {

    ExpressionTree getExpression();

    BlockStatementTree getBody();
}

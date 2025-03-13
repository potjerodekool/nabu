package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public interface ForStatement extends Statement {

    Statement getForInit();

    ExpressionTree getExpression();

    ExpressionTree getForUpdate();

    Statement getStatement();

    ForStatementBuilder builder();
}

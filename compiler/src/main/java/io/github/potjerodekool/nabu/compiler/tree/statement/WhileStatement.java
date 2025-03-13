package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public interface WhileStatement extends Statement {

    ExpressionTree getCondition();

    Statement getBody();

    WhileStatementBuilder builder();
}

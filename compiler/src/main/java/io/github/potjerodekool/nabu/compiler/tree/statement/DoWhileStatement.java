package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public interface DoWhileStatement extends Statement {

    Statement getBody();

    ExpressionTree getCondition();

    DoWhileStatementBuilder builder();
}

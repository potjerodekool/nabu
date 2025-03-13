package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public interface EnhancedForStatement extends Statement {

    VariableDeclarator getLocalVariable();

    ExpressionTree getExpression();

    Statement getStatement();

    EnhancedForStatementBuilder builder();
}

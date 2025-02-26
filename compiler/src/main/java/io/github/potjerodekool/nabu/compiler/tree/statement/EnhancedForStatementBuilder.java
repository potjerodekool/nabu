package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class EnhancedForStatementBuilder extends StatementBuilder<EnhancedForStatement> {

    CVariableDeclaratorStatement localVariable;
    ExpressionTree expression;
    Statement statement;

    public EnhancedForStatementBuilder(final EnhancedForStatement enhancedForStatement) {
        super(enhancedForStatement);
        this.localVariable = enhancedForStatement.localVariable;
        this.expression = enhancedForStatement.expression;
        this.statement = enhancedForStatement.statement;
    }

    @Override
    public EnhancedForStatementBuilder self() {
        return null;
    }

    public EnhancedForStatementBuilder localVariable(final CVariableDeclaratorStatement localVariable) {
        this.localVariable = localVariable;
        return this;
    }

    public EnhancedForStatementBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public EnhancedForStatementBuilder statement(final Statement statement) {
        this.statement = statement;
        return this;
    }

    @Override
    public EnhancedForStatement build() {
        return new EnhancedForStatement(this);
    }
}

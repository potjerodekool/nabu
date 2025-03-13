package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CEnhancedForStatement;

public class EnhancedForStatementBuilder extends StatementBuilder<EnhancedForStatement, EnhancedForStatementBuilder> {

    private VariableDeclarator localVariable;
    private ExpressionTree expression;
    private Statement statement;

    public EnhancedForStatementBuilder(final EnhancedForStatement enhancedForStatement) {
        super(enhancedForStatement);
        this.localVariable = enhancedForStatement.getLocalVariable();
        this.expression = enhancedForStatement.getExpression();
        this.statement = enhancedForStatement.getStatement();
    }

    public VariableDeclarator getLocalVariable() {
        return localVariable;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public Statement getStatement() {
        return statement;
    }

    @Override
    public EnhancedForStatementBuilder self() {
        return null;
    }

    public EnhancedForStatementBuilder localVariable(final VariableDeclarator localVariable) {
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
        return new CEnhancedForStatement(this);
    }
}

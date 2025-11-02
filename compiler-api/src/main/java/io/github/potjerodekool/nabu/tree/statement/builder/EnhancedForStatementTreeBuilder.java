package io.github.potjerodekool.nabu.tree.statement.builder;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.EnhancedForStatementTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CEnhancedForStatementTree;

/**
 * Builder for Enhanced For statements.
 */
public class EnhancedForStatementTreeBuilder extends StatementTreeBuilder<EnhancedForStatementTreeBuilder> {

    private VariableDeclaratorTree localVariable;
    private ExpressionTree expression;
    private StatementTree statement;

    public EnhancedForStatementTreeBuilder(final EnhancedForStatementTree enhancedForStatement) {
        super(enhancedForStatement);
        this.localVariable = enhancedForStatement.getLocalVariable();
        this.expression = enhancedForStatement.getExpression();
        this.statement = enhancedForStatement.getStatement();
    }

    public VariableDeclaratorTree getLocalVariable() {
        return localVariable;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public StatementTree getStatement() {
        return statement;
    }

    @Override
    public EnhancedForStatementTreeBuilder self() {
        return null;
    }

    public EnhancedForStatementTreeBuilder localVariable(final VariableDeclaratorTree localVariable) {
        this.localVariable = localVariable;
        return this;
    }

    public EnhancedForStatementTreeBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public EnhancedForStatementTreeBuilder statement(final StatementTree statement) {
        this.statement = statement;
        return this;
    }

    @Override
    public EnhancedForStatementTree build() {
        return new CEnhancedForStatementTree(this);
    }
}

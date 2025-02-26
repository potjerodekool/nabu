package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class EnhancedForStatement extends Statement {

    final CVariableDeclaratorStatement localVariable;
    final ExpressionTree expression;
    final Statement statement;

    public EnhancedForStatement(final CVariableDeclaratorStatement localVariable,
                                final ExpressionTree expression,
                                final Statement statement) {
        this.localVariable = localVariable;
        this.expression = expression;
        this.statement = statement;
    }

    public EnhancedForStatement(final EnhancedForStatementBuilder enhancedForStatementBuilder) {
        super(enhancedForStatementBuilder);
        this.localVariable = enhancedForStatementBuilder.localVariable;
        this.expression = enhancedForStatementBuilder.expression;
        this.statement = enhancedForStatementBuilder.statement;
    }

    public CVariableDeclaratorStatement getLocalVariable() {
        return localVariable;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public Statement getStatement() {
        return statement;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitEnhancedForStatement(this, param);
    }

    public EnhancedForStatementBuilder builder() {
        return new EnhancedForStatementBuilder(this);
    }

}

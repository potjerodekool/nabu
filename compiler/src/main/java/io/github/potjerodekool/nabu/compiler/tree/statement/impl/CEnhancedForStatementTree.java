package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.EnhancedForStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.EnhancedForStatementTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;

public class CEnhancedForStatementTree extends CStatementTree implements EnhancedForStatementTree {

    private final VariableDeclaratorTree localVariable;
    private final ExpressionTree expression;
    private final StatementTree statement;

    public CEnhancedForStatementTree(final VariableDeclaratorTree localVariable,
                                     final ExpressionTree expression,
                                     final StatementTree statement,
                                     final int lineNumber,
                                     final int columnNumber) {
        super(lineNumber, columnNumber);
        this.localVariable = localVariable;
        this.expression = expression;
        this.statement = statement;
    }

    public CEnhancedForStatementTree(final EnhancedForStatementTreeBuilder enhancedForStatementTreeBuilder) {
        super(enhancedForStatementTreeBuilder);
        this.localVariable = enhancedForStatementTreeBuilder.getLocalVariable();
        this.expression = enhancedForStatementTreeBuilder.getExpression();
        this.statement = enhancedForStatementTreeBuilder.getStatement();
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
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitEnhancedForStatement(this, param);
    }

    @Override
    public EnhancedForStatementTreeBuilder builder() {
        return new EnhancedForStatementTreeBuilder(this);
    }

}

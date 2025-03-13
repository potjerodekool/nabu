package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.EnhancedForStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.EnhancedForStatementBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclarator;

public class CEnhancedForStatement extends CStatement implements EnhancedForStatement {

    private final VariableDeclarator localVariable;
    private final ExpressionTree expression;
    private final Statement statement;

    public CEnhancedForStatement(final VariableDeclarator localVariable,
                                 final ExpressionTree expression,
                                 final Statement statement,
                                 final int lineNumber,
                                 final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.localVariable = localVariable;
        this.expression = expression;
        this.statement = statement;
    }

    public CEnhancedForStatement(final EnhancedForStatementBuilder enhancedForStatementBuilder) {
        super(enhancedForStatementBuilder);
        this.localVariable = enhancedForStatementBuilder.getLocalVariable();
        this.expression = enhancedForStatementBuilder.getExpression();
        this.statement = enhancedForStatementBuilder.getStatement();
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
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitEnhancedForStatement(this, param);
    }

    @Override
    public EnhancedForStatementBuilder builder() {
        return new EnhancedForStatementBuilder(this);
    }

}

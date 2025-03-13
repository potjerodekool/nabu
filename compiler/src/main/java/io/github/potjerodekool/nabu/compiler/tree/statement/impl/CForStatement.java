package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.ForStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.ForStatementBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;

public class CForStatement extends CStatement implements ForStatement {

    private final Statement forInit;
    private final ExpressionTree expression;
    private final ExpressionTree forUpdate;
    private final Statement statement;

    public CForStatement(final Statement forInit,
                         final ExpressionTree expression,
                         final ExpressionTree forUpdate,
                         final Statement statement,
                         final int lineNumber,
                         final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.forInit = forInit;
        this.expression = expression;
        this.forUpdate = forUpdate;
        this.statement = statement;
    }

    public CForStatement(final ForStatementBuilder builder) {
        super(builder);
        this.forInit = builder.getForInit();
        this.expression = builder.getExpression();
        this.forUpdate = builder.getForUpdate();
        this.statement = builder.getStatement();
    }

    public Statement getForInit() {
        return forInit;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public ExpressionTree getForUpdate() {
        return forUpdate;
    }

    public Statement getStatement() {
        return statement;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitForStatement(this, param);
    }

    @Override
    public ForStatementBuilder builder() {
        return new ForStatementBuilder(this);
    }

}

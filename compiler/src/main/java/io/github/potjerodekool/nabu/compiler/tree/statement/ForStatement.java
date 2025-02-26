package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class ForStatement extends Statement {

    private final Statement forInit;
    private final ExpressionTree expression;
    private final ExpressionTree forUpdate;
    private final Statement statement;


    public ForStatement(final Statement forInit,
                        final ExpressionTree expression,
                        final ExpressionTree forUpdate,
                        final Statement statement) {
        this.forInit = forInit;
        this.expression = expression;
        this.forUpdate = forUpdate;
        this.statement = statement;
    }

    public ForStatement(final ForStatementBuilder builder) {
        super(builder);
        this.forInit = builder.forInit;
        this.expression = builder.expression;
        this.forUpdate = builder.forUpdate;
        this.statement = builder.statement;
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

    public ForStatementBuilder builder() {
        return new ForStatementBuilder(this);
    }

}

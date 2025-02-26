package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class BlockStatement extends Statement {

    private final List<Statement> statements = new ArrayList<>();

    public BlockStatement() {
    }

    public BlockStatement(final List<Statement> statements) {
        statements.forEach(Objects::requireNonNull);
        this.statements.addAll(statements);
    }

    public BlockStatement(final BlockStatementBuilder builder) {
        super(builder);
        this.statements.addAll(builder.statements);
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitBlockStatement(this, param);
    }

    public BlockStatement statement(final Statement statement) {
        Objects.requireNonNull(statement);
        this.statements.add(statement);
        return this;
    }

    public BlockStatement statement(final List<Statement> statements) {
        statements.forEach(Objects::requireNonNull);
        this.statements.addAll(statements);
        return this;
    }

    public BlockStatement statement(final ExpressionTree expression) {
        final var se = new StatementExpression(expression);
        se.setLineNumber(expression.getLineNumber());
        return statement(se);
    }

    public List<Statement> getStatements() {
        return statements;
    }

    public BlockStatementBuilder builder() {
        return new BlockStatementBuilder(this);
    }

}

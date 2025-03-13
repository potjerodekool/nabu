package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatementBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CBlockStatement extends CStatement implements BlockStatement {

    private final List<Statement> statements = new ArrayList<>();

    public CBlockStatement(final List<Statement> statements,
                           final int lineNumber,
                           final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.statements.addAll(statements);
    }

    public CBlockStatement(final BlockStatementBuilder builder) {
        super(builder);
        this.statements.addAll(builder.getStatements());
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitBlockStatement(this, param);
    }

    public CBlockStatement statement(final Statement statement) {
        Objects.requireNonNull(statement);
        this.statements.add(statement);
        return this;
    }

    public CBlockStatement statement(final List<Statement> statements) {
        statements.forEach(Objects::requireNonNull);
        this.statements.addAll(statements);
        return this;
    }

    public CBlockStatement statement(final ExpressionTree expression) {
        final var expressionStatement = TreeMaker.expressionStatement(expression, expression.getLineNumber(), expression.getColumnNumber());
        return statement(expressionStatement);
    }

    public List<Statement> getStatements() {
        return statements;
    }

    @Override
    public void addStatement(final Statement statement) {
        this.statements.add(statement);
    }

    @Override
    public BlockStatementBuilder builder() {
        return new BlockStatementBuilder(this);
    }

}

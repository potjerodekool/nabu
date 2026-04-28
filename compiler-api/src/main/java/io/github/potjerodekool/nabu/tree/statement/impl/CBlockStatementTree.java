package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.builder.BlockStatementTreeBuilder;
import io.github.potjerodekool.nabu.util.CollectionUtils;

import java.util.*;

/**
 * Implementation of BlockStatement.
 */
public class CBlockStatementTree extends CStatementTree implements BlockStatementTree {

    private final List<StatementTree> statements = new ArrayList<>();

    public CBlockStatementTree(final List<StatementTree> statements) {
        this(statements, -1, -1);
    }

    public CBlockStatementTree(final List<StatementTree> statements,
                               final int lineNumber,
                               final int columnNumber) {
        super(lineNumber, columnNumber);
        statement(statements);
    }

    public CBlockStatementTree(final BlockStatementTreeBuilder builder) {
        super(builder);
        statement(builder.getStatements());
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitBlockStatement(this, param);
    }

    public CBlockStatementTree statement(final StatementTree statement) {
        Objects.requireNonNull(statement);
        this.statements.add(statement);
        return this;
    }

    public CBlockStatementTree statement(final List<StatementTree> statements) {
        statements.forEach(Objects::requireNonNull);
        this.statements.addAll(statements);
        return this;
    }

    public CBlockStatementTree statement(final ExpressionTree expression) {
        Objects.requireNonNull(expression);
        final var expressionStatement = TreeMaker.expressionStatement(expression, expression.getLineNumber(), expression.getColumnNumber());
        return statement(expressionStatement);
    }

    public List<StatementTree> getStatements() {
        return statements;
    }

    @Override
    public void addStatement(final StatementTree statement) {
        Objects.requireNonNull(statement);
        this.statements.add(statement);
    }

    @Override
    public void addStatements(final List<StatementTree> statements) {
        statements.forEach(Objects::requireNonNull);
        this.statements.addAll(statements);
    }

    @Override
    public BlockStatementTreeBuilder builder() {
        return new BlockStatementTreeBuilder(this);
    }

    @Override
    public List<? extends Tree> children() {
        return Collections.unmodifiableList(statements);
    }
}

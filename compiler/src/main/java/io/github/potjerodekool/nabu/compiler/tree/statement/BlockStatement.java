package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.CExpression;

import java.util.ArrayList;
import java.util.List;

public class BlockStatement extends Statement {

    private final List<Statement> statements = new ArrayList<>();

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitBlockStatement(this, param);
    }

    public BlockStatement statement(final Statement statement) {
        if (statement == null) {
            throw new NullPointerException();
        }

        this.statements.add(statement);
        return this;
    }

    public BlockStatement statement(final List<Statement> statements) {
        for (final Statement statement : statements) {
            if (statement == null) {
                throw new NullPointerException();
            }
        }
        this.statements.addAll(statements);
        return this;
    }

    public BlockStatement statement(final CExpression expression) {
        return statement(new StatementExpression(expression));
    }

    public List<Statement> getStatements() {
        return statements;
    }

    public BlockStatementBuilder builder() {
        return new BlockStatementBuilder(this);
    }

    public static class BlockStatementBuilder extends StatementBuilder<BlockStatement> {

        private final List<Statement> statements = new ArrayList<>();

        protected BlockStatementBuilder(final BlockStatement original) {
            super(original);
        }

        public BlockStatementBuilder statements(final List<Statement> statements) {
            this.statements.addAll(statements);
            return this;
        }

        @Override
        public BlockStatement build() {
            return fill(new BlockStatement()
                    .statement(statements));
        }
    }
}

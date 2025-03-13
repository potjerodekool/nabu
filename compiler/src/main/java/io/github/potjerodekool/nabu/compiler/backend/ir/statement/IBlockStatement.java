package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;

import java.util.ArrayList;
import java.util.List;

public class IBlockStatement extends IStatement {

    private final List<IStatement> statements = new ArrayList<>();

    public IBlockStatement(final List<IStatement> statements) {
        this.statements.addAll(statements);
    }

    public List<IStatement> getStatements() {
        return statements;
    }

    @Override
    public <P> void accept(final CodeVisitor<P> visitor, final P param) {
        visitor.visitBlockStatement(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return List.of();
    }

    @Override
    public IStatement build(final List<IExpression> kids) {
        return this;
    }
}

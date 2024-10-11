package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;

import java.util.Collections;
import java.util.List;

public class IExpressionStatement extends IStatement {

    private final IExpression exp;

    public IExpressionStatement(final IExpression exp) {
        this.exp = exp;
    }

    public IExpressionStatement(final IExpression exp,
                                final int lineNumber) {
        this.exp = exp;
        setLineNumber(lineNumber);
    }

    public IExpression getExp() {
        return exp;
    }

    @Override
    public <P> void accept(final CodeVisitor<P> visitor, final P param) {
        visitor.visitExpressionStatement(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return exp == null ? Collections.emptyList() : Collections.singletonList(exp);
    }


    @Override
    public IStatement build(final List<IExpression> kids) {
        return new IExpressionStatement(kids.getFirst(), getLineNumber());
    }

}

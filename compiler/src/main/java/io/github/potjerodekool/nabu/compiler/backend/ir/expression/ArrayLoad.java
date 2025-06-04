package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.List;

public class ArrayLoad extends IExpression {

    private final IExpression expression;
    private final IExpression indexExpression;

    public ArrayLoad(final IExpression expression,
                     final IExpression indexExpression) {
        this.expression = expression;
        this.indexExpression = indexExpression;
    }

    public IExpression getExpression() {
        return expression;
    }

    public IExpression getIndexExpression() {
        return indexExpression;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitArrayLoad(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return List.of(expression, indexExpression);
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return this;
    }
}

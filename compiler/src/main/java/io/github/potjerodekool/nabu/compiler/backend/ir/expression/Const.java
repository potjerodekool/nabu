package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.Collections;
import java.util.List;

public class Const extends IExpression {

    private final Object value;

    public Const(final Object value) {
        this.value = value;
    }

    public Object getValue() {
        return value;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitConst(this, param);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    @Override
    public List<IExpression> kids() {
        return Collections.emptyList();
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return this;
    }

}

package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

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

    public static boolean isNop(final Const c) {
        return Objects.equals(c.getValue(), 1) || Objects.equals(c.getValue(), 0);
    }

    public static IExpression constExp(final Object value) {
        return new Const(value);
    }

    public Const negate() {
        if (value instanceof Integer) {
            return new Const(-(Integer)value);
        } else {
            throw new TodoException("" + value.getClass());
        }
    }
}

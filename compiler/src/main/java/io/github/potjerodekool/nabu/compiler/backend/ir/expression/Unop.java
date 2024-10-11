package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.Collections;
import java.util.List;

public class Unop extends IExpression {

    public enum Oper {
        PLUS_PLUS,
        MIN_MIN,
        BANG,
        NOT,
        BOC//Binary Ones Complement
    }

    private final Oper operator;
    private final IExpression expression;
    private final boolean prefix;

    public Unop(final Oper operator,
                final IExpression expression,
                final boolean prefix) {
        if (expression == null) {
            throw new NullPointerException();
        }

        this.operator = operator;
        this.expression = expression;
        this.prefix = prefix;
    }

    public Oper getOperator() {
        return operator;
    }

    public IExpression getExpression() {
        return expression;
    }

    private boolean isPrefix() {
        return prefix;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitUnop(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return Collections.singletonList(expression);
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return new Unop(operator, kids.getFirst(), prefix);
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();

        if (isPrefix()) {
            builder.append(operator).append(" ");
        }
        builder.append(expression).append(" ");
        if (!isPrefix()) {
            builder.append(operator);
        }

        return builder.toString();
    }
}

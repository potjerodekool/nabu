package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.Collections;
import java.util.List;

public class Mem extends IExpression {

    private final IExpression exp;

    public Mem(final IExpression exp) {
        if (exp instanceof Mem) {
            throw new IllegalArgumentException();
        }
        this.exp = exp;
    }

    public IExpression getExp() {
        return exp;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitMem(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return Collections.singletonList(exp);
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return new Mem(kids.getFirst());
    }

    @Override
    public String toString() {
        return "MEM[" + (exp == null ? "null" : exp.toString()) + "]";
    }

    public static Mem mem(final IExpression exp) {
        return new Mem(exp);
    }
}

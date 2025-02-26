package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.List;
import java.util.Objects;

public class Eseq extends IExpression {

    private final IStatement stm;
    private final IExpression exp;

    public Eseq(final IStatement s,
                final IExpression e) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(e);
        this.stm = s;
        this.exp = e;
    }

    public IExpression getExp() {
        return exp;
    }

    public IStatement getStm() {
        return stm;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitEseq(this, param);
    }

    @Override
    public String toString() {
        return stm + " " + exp + '\n';
    }

    @Override
    public List<IExpression> kids() {
        throw new UnsupportedOperationException();
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        throw new UnsupportedOperationException();
    }

    public static IExpression eseq(final IStatement stm, final IExpression exp) {
        return stm == null ? exp : new Eseq(stm, exp);
    }
}

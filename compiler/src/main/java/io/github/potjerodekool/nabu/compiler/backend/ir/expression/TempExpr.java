package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.Collections;
import java.util.List;

public class TempExpr extends IExpression {

    private final Temp temp;

    public TempExpr(final int index) {
        this(new Temp(index));
    }

    public TempExpr(final Temp temp) {
        this.temp = temp;
    }

    public Temp getTemp() {
        return temp;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitTemp(this, param);
    }

    @Override
    public String toString() {
        return temp.toString();
    }

    @Override
    public List<IExpression> kids() {
        return Collections.emptyList();
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return this;
    }

    public static IExpression temp(final Temp temp) {
        return new TempExpr(temp);
    }
}

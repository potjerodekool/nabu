package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.Collections;
import java.util.List;

public class TempExpr extends IExpression {

    private final Temp temp;
    private final Frame frame;
    private final IType type;

    public TempExpr() {
        this.temp = new Temp(-1);
        this.frame = null;
        this.type = null;
    }

    public TempExpr(final int index,
                    final Frame frame) {
        this(index, frame, null);
    }

    public TempExpr(final int index,
                    final Frame frame,
                    final IType type) {
        this.temp = new Temp(index);
        this.frame = frame;
        this.type = type;
    }

    public Temp getTemp() {
        return temp;
    }

    public Frame getFrame() {
        return frame;
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
}

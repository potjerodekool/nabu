package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.Collections;
import java.util.List;

public class Name extends IExpression {

    private final ILabel label;

    public Name(final String name) {
        this(new ILabel(name));
    }

    public Name(final ILabel label) {
        this.label = label;
    }

    public ILabel getLabel() {
        return label;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitName(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return Collections.emptyList();
    }

    @Override
    public IExpression build(List<IExpression> kids) {
        return this;
    }

    @Override
    public String toString() {
        return label.toString();
    }

    public static Name name(final ILabel label) {
        return new Name(label);
    }

    public static Name name(final String name) {
        return name(new ILabel(name));
    }
}

package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.Collections;
import java.util.List;

public class ILabelStatement extends IStatement {

    private final ILabel label;

    public ILabelStatement() {
        this(new ILabel());
    }

    public ILabelStatement(final ILabel label) {
        this.label = label;
    }

    public ILabel getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label.toString() + "\n";
    }

    @Override
    public <P> void accept(final CodeVisitor<P> visitor, final P param) {
        visitor.visitLabelStatement(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return Collections.emptyList();
    }

    @Override
    public IStatement build(final List<IExpression> kids) {
        return this;
    }

    public static ILabelStatement label(final ILabel label) {
        return new ILabelStatement(label);
    }

    @Override
    public List<ILabel> getJumpTargets() {
        return Collections.emptyList();
    }
}

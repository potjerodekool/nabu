package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Exp;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ISwitchStatement extends IStatement {

    private final Exp condition;
    private final ILabel defaultLabel;
    private final int[] keys;
    private final ILabel[] labels;

    public ISwitchStatement(final Exp condition,
                            final ILabel defaultLabel,
                            final int[] keys,
                            final ILabel[] labels) {
        this.condition = condition;
        this.defaultLabel = defaultLabel;
        this.keys = keys;
        this.labels = labels;
    }

    public Exp getCondition() {
        return condition;
    }

    public ILabel getDefaultLabel() {
        return defaultLabel;
    }

    public int[] getKeys() {
        return keys;
    }

    public ILabel[] getLabels() {
        return labels;
    }

    @Override
    public <P> void accept(final CodeVisitor<P> visitor, final P param) {
        visitor.visitSwitchStatement(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return List.of();
    }

    @Override
    public IStatement build(final List<IExpression> kids) {
        return this;
    }

    @Override
    public List<ILabel> getJumpTargets() {
        final var list = new ArrayList<ILabel>(Arrays.asList(labels));
        list.add(defaultLabel);
        return list;
    }
}

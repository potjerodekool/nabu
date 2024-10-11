package io.github.potjerodekool.nabu.compiler.backend.ir.statement;


import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.Collections;
import java.util.List;

public class IThrowStatement extends IStatement {

    private final IExpression exp;

    private List<ILabel> labels = Collections.emptyList();

    public IThrowStatement(final IExpression exp) {
        this.exp = exp;
    }

    public IExpression getExp() {
        return exp;
    }

    @Override
    public <P> void accept(final CodeVisitor<P> visitor, final P param) {
        visitor.visitThrowStatement(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return Collections.emptyList();
    }

    @Override
    public IStatement build(final List<IExpression> kids) {
        return this;
    }

    @Override
    public String toString() {
        return "throws " + exp + '\n';
    }

    @Override
    public boolean isJump() {
        return true;
    }

    @Override
    public List<ILabel> getJumpTargets() {
        return labels;
    }

    public void setJumpTarget(final ILabel label) {
        this.labels = List.of(label);
    }
}

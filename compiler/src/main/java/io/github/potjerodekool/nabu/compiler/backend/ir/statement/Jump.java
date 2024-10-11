package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Name;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.Collections;
import java.util.List;

public class Jump extends IStatement {

    private final IExpression exp;
    private final List<ILabel> targets;

    public Jump(final ILabel target) {
        this.exp = new Name(target);
        this.targets = Collections.singletonList(target);
    }

    private Jump(final IExpression exp, final List<ILabel> targets) {
        this.exp = exp;
        this.targets = targets;
    }

    public IExpression getExp() {
        return exp;
    }

    @Override
    public String toString() {
        return "JUMP [" + exp.toString() + "]\n";
    }

    @Override
    public <P> void accept(final CodeVisitor<P> visitor, final P param) {
        visitor.visitJump(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return Collections.singletonList(exp);
    }

    @Override
    public IStatement build(final List<IExpression> kids) {
        return new Jump(kids.getFirst(), targets);
    }

    @Override
    public boolean isJump() {
        return true;
    }

    @Override
    public List<ILabel> getJumpTargets() {
        return targets;
    }

    public static IStatement jump(final ILabel label) {
        return new Jump(label);
    }

}

package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import static io.github.potjerodekool.nabu.compiler.backend.ir.statement.ILabelStatement.label;
import static io.github.potjerodekool.nabu.compiler.backend.ir.statement.Jump.jump;

public class WhileExp implements Exp {

    private final Exp condition;
    private final Exp body;
    private final ILabel test = new ILabel();
    private final ILabel t = new ILabel();
    private final ILabel f = new ILabel();

    public WhileExp(final Exp condition, final Exp body) {
        this.condition = condition;
        this.body = body;
    }

    @Override
    public IExpression unEx() {
        return null;
    }

    @Override
    public IStatement unNx() {
        return unCx(t, f);
    }

    @Override
    public IStatement unCx(final ILabel trueLabel,
                           final ILabel falseLabel) {
        return IStatement.seqs(
                label(test),
                condition.unCx(trueLabel, falseLabel),
                label(trueLabel),
                body.unNx(),
                jump(test),
                label(falseLabel)
        );
    }
}

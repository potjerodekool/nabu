package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import static io.github.potjerodekool.nabu.compiler.backend.ir.statement.ILabelStatement.label;

public class DoWhileExp implements Exp {

    private final Exp body;
    private final Exp condition;
    private final ILabel t = new ILabel();
    private final ILabel f = new ILabel();

    public DoWhileExp(final Exp body,
                      final Exp condition) {
        this.body = body;
        this.condition = flipCondition(condition);
    }

    @Override
    public IExpression unEx() {
        return null;
    }

    @Override
    public IStatement unNx() {
        return unCx(t,f);
    }

    @Override
    public IStatement unCx(final ILabel trueLabel, final ILabel falseLabel) {
        return IStatement.seqs(
                label(trueLabel),
                body.unNx(),
                condition.unCx(falseLabel, trueLabel),
                label(falseLabel)
        );
    }

    private Exp flipCondition(final Exp condition) {
        final var expr = condition.unEx().flipCompare();
        return new Ex(expr);
    }
}

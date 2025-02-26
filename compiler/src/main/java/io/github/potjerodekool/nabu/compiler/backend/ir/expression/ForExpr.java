package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IExpressionStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import static io.github.potjerodekool.nabu.compiler.backend.ir.statement.ILabelStatement.label;
import static io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement.seqs;
import static io.github.potjerodekool.nabu.compiler.backend.ir.statement.Jump.jump;

/**
 * For expression
 */
public class ForExpr implements Exp {

    private final Exp init;
    private final Exp condition;
    private final Exp update;

    private final Exp body;
    private final ILabel test = new ILabel();
    private final ILabel t = new ILabel();
    private final ILabel f = new ILabel();

    public ForExpr(final Exp init,
                   final Exp condition,
                   final Exp update,
                   final Exp body) {
        this.init = init;
        this.condition = condition;
        this.update = update;
        this.body = body;
    }

    @Override
    public IExpression unEx() {
        return null;
    }

    @Override
    public IStatement unNx() {
        return unCx(t,f);
    }

    private IStatement asStatement(final Exp ex) {
        final IStatement stm = ex.unNx();
        return stm != null ? stm : new IExpressionStatement(ex.unEx());
    }

    @Override
    public IStatement unCx(final ILabel trueLabel, final ILabel falseLabel) {
        final var initStatement = asStatement(init);
        final var updateStatement = asStatement(update);

        return seqs(
                initStatement,
                label(test),
                condition.unCx(trueLabel, falseLabel),
                label(trueLabel),
                body.unNx(),
                updateStatement,
                jump(test),
                label(falseLabel));
    }
}

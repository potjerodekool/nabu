package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import static io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement.seq;

public class IfThenElseExp implements Exp {

    private final Exp condition;
    private final Exp trueBranch;
    private final Exp falseBranch;
    private ILabel t = new ILabel();
    private ILabel f = new ILabel();
    private final ILabel join = new ILabel();

    public IfThenElseExp(final Exp condition,
                         final Exp trueBranch,
                         final Exp falseBranch) {
        this.condition = condition;
        this.trueBranch = trueBranch;
        this.falseBranch = falseBranch;
    }

    @Override
    public IExpression unEx() {
        final var aExp = trueBranch.unEx();
        if (aExp == null) {
            return null;
        }
        final var bExp = falseBranch.unEx();
        if (bExp == null) {
            return null;
        }
        final var r = new Temp();
        return eseq(
                seq(seq(condition.unCx(t, f),
                        seq(seq(label(t), seq(move(temp(r), aExp), jump(join))),
                                seq(label(f),
                                        seq(move(temp(r), bExp), jump(join))))),
                        label(join)), temp(r));
    }

    @Override
    public IStatement unNx() {
        IStatement aStm = trueBranch.unNx();
        if (aStm == null) {
            t = join;
        } else {
            aStm = seq(seq(label(t), aStm), jump(join));
        }

        IStatement bStm = falseBranch.unNx();
        if (bStm == null) {
            f = join;
        } else {
            bStm = seq(seq(label(f), bStm), jump(join));
        }

        if (aStm == null && bStm == null) {
            return condition.unNx();
        }

        final IStatement condStm = condition.unCx(t, f);

        if (aStm == null) {
            return seq(seq(condStm, bStm), label(join));
        }

        if (bStm == null) {
            return seq(seq(condStm, aStm), label(join));
        }

        return seq(seq(condStm, seq(aStm, bStm)), label(join));
    }

    @Override
    public IStatement unCx(final ILabel tt, final ILabel ff) {
        IStatement aStm = trueBranch.unCx(tt, ff);
        if (aStm instanceof Jump jump) {
            if (jump.getExp() instanceof Name name) {
                aStm = null;
                t = name.getLabel();
            }
        }
        IStatement bStm = falseBranch.unCx(tt, ff);
        if (bStm instanceof Jump jump) {
            if (jump.getExp() instanceof Name name) {
                bStm = null;
                f = name.getLabel();
            }
        }

        final IStatement condStm = condition.unCx(t, f);

        if (aStm == null && bStm == null) {
            return condStm;
        }
        if (aStm == null) {
            return seq(condStm, seq(label(f), bStm));
        }
        if (bStm == null) {
            return seq(condStm, seq(label(t), aStm));
        }
        return seq(condStm, seq(seq(label(t), aStm), seq(label(f), bStm)));
    }

    private ILabelStatement label(final ILabel l) {
        return new ILabelStatement(l);
    }

    private IStatement jump(final ILabel target) {
        return new Jump(target);
    }

    private IExpression eseq(final IStatement stm, final IExpression exp) {
        return stm == null ? exp : new Eseq(stm, exp);
    }

    private IStatement move(final IExpression dst, final IExpression src) {
        return new Move(dst, src);
    }

    private IExpression temp(final Temp t) {
        return new TempExpr(t.getIndex(), null);
    }
}

package io.github.potjerodekool.nabu.compiler.backend.postir.canon;


import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IExpressionStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Move;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Seq;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static io.github.potjerodekool.nabu.compiler.CollectionUtils.headAndTailList;
import static io.github.potjerodekool.nabu.compiler.CollectionUtils.tailOf;

public class Canon {

    private static final List<IStatement> NULL_STM_LIST = Collections.emptyList();
    private static final List<IExpression> NULL_EXP_LIST = Collections.emptyList();
    private static final StmExpList NOP_NULL = new StmExpList(new IExpressionStatement(new Const(0)), NULL_EXP_LIST);

    private Canon() {
    }

    private static boolean isNop(final IStatement a) {
        return a instanceof IExpressionStatement
                && ((IExpressionStatement) a).getExp() instanceof Const;
    }

    private static IStatement seq(final IStatement a, final IStatement b) {
        final IStatement result;

        if (isNop(a)) {
            result = b;
        } else if (isNop(b)) {
            result = a;
        } else {
            result = isNop(a) ? b : new Seq(a, b);
        }

        validate(result);
        return result;
    }

    private static boolean commute(final IStatement a, final IExpression b) {
        return isNop(a) ||
                b instanceof Name ||
                b instanceof Const;
    }

    private static IStatement doSeqStatement(final Seq s) {
        return seq(doStatement(s.getLeft()), doStatement(s.getRight()));
    }

    private static IStatement doMoveStatement(final Move s) {
        IStatement result;

        if (s.getDst() instanceof TempExpr
                && s.getSrc() instanceof Call call) {
            result = reorderStatement(new MoveCall(s.getDst(), call));
        } else if (s.getDst() instanceof Eseq eseq) {
            result = doSeqStatement(new Seq(eseq.getStm(), new Move(eseq.getExp(), s.getSrc())));
        } else {
            result = reorderStatement(s);
        }

        validate(result);

        return result;
    }

    private static void validate(final Object obj) {
        if (obj == null) {
            throw new NullPointerException();
        }
    }

    private static IStatement doExpressionStatement(final IExpressionStatement s) {
        if (s.getExp() instanceof Call call) {
            return reorderStatement(new ExpCall(call));
        } else {
            return reorderStatement(s);
        }
    }

    private static IStatement doStatement(final IStatement s) {
        return switch (s) {
            case Seq seq -> doSeqStatement(seq);
            case Move move -> doMoveStatement(move);
            case IExpressionStatement expressionStatement -> doExpressionStatement(expressionStatement);
            default -> reorderStatement(s);
        };
    }

    private static IStatement reorderStatement(final IStatement s) {
        final StmExpList x = reorder(s.kids());
        final var result = seq(x.stm, s.build(x.exps));
        validate(result);
        return result;
    }

    private static Eseq doEseqExpression(final Eseq e) {
        final IStatement stms = doStatement(e.getStm());
        final Eseq b = doExpression(e.getExp());
        return new Eseq(seq(stms, b.getStm()), b.getExp());
    }

    private static Eseq doExpression(final IExpression e) {
        return e instanceof Eseq eseq ? doEseqExpression(eseq) : reorderExp(e);
    }

    private static Eseq reorderExp(final IExpression e) {
        if (e == null) {
            throw new NullPointerException();
        }

        final StmExpList x = reorder(e.kids());
        return new Eseq(x.stm, e.build(x.exps));
    }

    private static StmExpList reorder(final List<IExpression> exps) {
        if (exps.isEmpty()) {
            return NOP_NULL;
        } else {
            final IExpression a = exps.getFirst();
            if (a instanceof Call) {
                final Temp t = new Temp();
                final IExpression e = new Eseq(new Move(new TempExpr(t), a),
                        new TempExpr(t));
                return reorder(headAndTailList(e, tailOf(exps)));
            } else {
                final Eseq aa = doExpression(a);
                final StmExpList bb = reorder(tailOf(exps));
                if (commute(bb.stm, aa.getExp())) {
                    return new StmExpList(seq(aa.getStm(), bb.stm),
                            headAndTailList(aa.getExp(), bb.exps));
                } else {
                    final Temp t = new Temp();
                    return new StmExpList(
                            seq(aa.getStm(),
                                    seq(new Move(new TempExpr(t), aa.getExp()),
                                            bb.stm)),
                            headAndTailList(new TempExpr(t), bb.exps));
                }
            }
        }
    }

    private static List<IStatement> linear(final Seq s, final List<IStatement> l) {
        return linear(s.getLeft(), linear(s.getRight(), l));
    }

    private static List<IStatement> linear(final IStatement s, final List<IStatement> l) {
        return s instanceof Seq seq ? linear(seq, l) : headAndTailList(s, l);
    }

    public static List<IStatement> linearize(final IStatement s) {
        final List<IStatement> list = linear(doStatement(s), NULL_STM_LIST);
        cleanUp(list);
        return list;
    }

    private static void cleanUp(final List<IStatement> list) {
        final Iterator<IStatement> iter = list.iterator();

        while (iter.hasNext()) {
            final IStatement statement = iter.next();

            if (statement instanceof IExpressionStatement) {
                IExpressionStatement expressionStatement = (IExpressionStatement) statement;

                if (expressionStatement.getExp() instanceof Const cnst
                        && Const.isNop(cnst)) {
                    iter.remove();
                }
            }
        }
    }

}

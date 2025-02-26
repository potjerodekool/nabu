package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.CJump;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IExpressionStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Jump;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.tree.Tag;

public class Ex implements Exp {
    private final IExpression exp;

    public Ex(final IExpression e) {
        exp = e;
    }

    @Override
    public IExpression unEx() {
        return exp;
    }

    @Override
    public IStatement unNx() {
        return new IExpressionStatement(exp);
    }

    @Override
    public IStatement unCx(final ILabel t, final ILabel f) {
        // if the exp when a constant, emit JUMP statement.
        if (exp instanceof Const c) {
            if (c.getValue() instanceof Integer) {
                final int val = (int) c.getValue();

                if (val == 0) {
                    return new Jump(f);
                } else {
                    return new Jump(t);
                }
            }
        }
        return new CJump(Tag.EQ, exp, new Const(1), t, f);
    }

}

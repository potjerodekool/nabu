package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.INode;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.List;

public abstract class IStatement extends INode {

    public abstract <P> void accept(CodeVisitor<P> visitor, P param);

    public abstract List<IExpression> kids();

    public abstract IStatement build(List<IExpression> kids);

    public boolean isJump() {
        return false;
    }

    public List<ILabel> getJumpTargets() {
        return List.of();
    }

    public static IStatement seq(final IStatement left,
                                 final IStatement right) {
        return left == null ? right : right == null ? left : new Seq(left, right);
    }

    public static IStatement seq(final List<IStatement> statements) {
        return statements.stream()
                .reduce(Seq::new)
                .orElse(null);
    }

    public static IStatement seqs(final IStatement... statements) {
        if (statements.length == 0) {
            return null;
        } else {
            IStatement stm = statements[0];

            for (int i = 1; i < statements.length; i++) {
                stm = seq(stm, statements[i]);
            }
            return stm;
        }
    }
}

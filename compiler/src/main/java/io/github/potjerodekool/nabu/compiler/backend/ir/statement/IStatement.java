package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.List;

public abstract class IStatement {

    private int lineNumber = -1;

    public abstract <P> void accept(CodeVisitor<P> visitor, P param);

    public abstract List<IExpression> kids();

    public abstract IStatement build(List<IExpression> kids);

    public boolean isJump() {
        return false;
    }

    public List<ILabel> getJumpTargets() {
        return List.of();
    }

    public static IStatement seq(final IStatement left, final IStatement right) {
        return left == null ? right : right == null ? left : new Seq(left, right);
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(final int lineNumber) {
        this.lineNumber = lineNumber;
    }
}

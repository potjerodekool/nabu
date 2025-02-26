package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;

import java.util.List;

public class Seq extends IStatement {

    private final IStatement left;
    private final IStatement right;

    public Seq(final IStatement left,
               final IStatement right) {
        this.left = left;
        this.right = right;
    }

    public IStatement getLeft() {
        return left;
    }

    public IStatement getRight() {
        return right;
    }

    @Override
    public String toString() {
        return String.valueOf(left) + right;
    }

    @Override
    public <P> void accept(final CodeVisitor<P> visitor, final P param) {
        visitor.visitSeq(this, param);
    }

    @Override
    public List<IExpression> kids() {
        throw new UnsupportedOperationException();
    }

    @Override
    public IStatement build(final List<IExpression> kids) {
        throw new UnsupportedOperationException();
    }

}

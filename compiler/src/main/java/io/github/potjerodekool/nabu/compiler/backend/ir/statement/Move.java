package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Mem;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Move extends IStatement {

    private final IExpression src;

    private final IExpression dst;

    public Move(final IExpression src,
                final IExpression dst) {
        this(src, dst, -1);
    }

    public Move(final IExpression src,
                final IExpression dst,
                final int lineNumber) {
        Objects.requireNonNull(src);
        Objects.requireNonNull(dst);
        this.src = src;
        this.dst = dst;
        setLineNumber(lineNumber);
    }

    public IExpression getSrc() {
        return src;
    }

    public IExpression getDst() {
        return dst;
    }


    @Override
    public <P> void accept(final CodeVisitor<P> visitor, final P param) {
        visitor.visitMove(this, param);
    }

    @Override
    public List<IExpression> kids() {
        if (dst instanceof Mem) {
            return List.of(((Mem) dst).getExp(), src);
        } else {
            return Collections.singletonList(src);
        }
    }

    @Override
    public IStatement build(final List<IExpression> kids) {
        final IStatement statement;

        if (dst instanceof Mem) {
            statement = new Move(new Mem(kids.get(0)), kids.get(1));
        } else {
            statement = new Move(dst, kids.getFirst());
        }
        return statement;
    }
}

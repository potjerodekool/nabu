package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.List;

public class InstExpression extends IExpression {

    public enum Kind {
        DUP,
        ARRAY_STORE
    }

    private final Kind kind;

    public static InstExpression dup() {
        return new InstExpression(Kind.DUP);
    }

    public static InstExpression arrayStore() {
        return new InstExpression(Kind.ARRAY_STORE);
    }

    public InstExpression(final Kind kind) {
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitInstExpression(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return List.of();
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return null;
    }
}

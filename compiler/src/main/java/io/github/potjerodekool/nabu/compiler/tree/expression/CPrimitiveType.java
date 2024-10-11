package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class CPrimitiveType extends CExpression {

    private final Kind kind;

    public CPrimitiveType(final Kind kind) {
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitPrimitiveType(this, param);
    }

    public enum Kind {
        BOOLEAN,
        INT,
        BYTE,
        SHORT,
        LONG,
        CHAR,
        FLOAT,
        DOUBLE
    }
}

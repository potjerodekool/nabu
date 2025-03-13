package io.github.potjerodekool.nabu.compiler.tree.expression;

public interface PrimitiveTypeTree extends ExpressionTree {

    Kind getKind();

    enum Kind {
        BOOLEAN,
        INT,
        BYTE,
        SHORT,
        LONG,
        CHAR,
        FLOAT,
        DOUBLE,
        VOID
    }
}

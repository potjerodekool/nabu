package io.github.potjerodekool.nabu.tree.expression;

/**
 * Primitive type.
 */
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

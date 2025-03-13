package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CLiteralExpressionTree;

import java.util.Arrays;

public interface LiteralExpressionTree extends ExpressionTree {

    Kind getLiteralKind();

    void setLiteralKind(Kind kind);

    Object getLiteral();

    void setLiteral(Object literal);

    LiteralExpressionTree negate();

    CLiteralExpressionTree.LiteralExpressionTreeBuilder builder();

    enum Kind {
        INTEGER(Integer.class),
        LONG(Long.class),
        BOOLEAN(Boolean.class),
        STRING(String.class),
        NULL(null),
        CLASS(Class.class),
        BYTE(Byte.class),
        SHORT(Short.class),
        FLOAT(Float.class),
        DOUBLE(Double.class),
        CHAR(Character.class);

        private final Class<?> type;

        Kind(final Class<?> type) {
            this.type = type;
        }

        public static Kind resolveKind(final Object value) {
            if (value == null) {
                return NULL;
            }
            return Arrays.stream(values())
                    .filter(e -> value.getClass() == e.type
                    ).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            value.getClass().getName()
                    ));
        }
    }

}

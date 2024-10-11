package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.Arrays;
import java.util.Objects;

public class LiteralExpression extends CExpression {

    private Object literal;
    private final Kind literalKind;


    public LiteralExpression(final Object literal) {
        this(literal, Kind.resolveKind(literal));
    }

    public LiteralExpression(final Object literal,
                             final Kind kind) {
        this.literal = literal;
        this.literalKind = kind;
    }

    public Kind getLiteralKind() {
        return literalKind;
    }

    public Object getLiteral() {
        return literal;
    }

    public LiteralExpression literal(final Object literal) {
        this.literal = literal;
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitLiteralExpression(this, param);
    }

    public enum Kind {
        NULL(null),
        STRING(String.class),
        CLASS(Class.class),
        BOOLEAN(Boolean.class);

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

    @Override
    public String toString() {
        if (getLiteralKind() == Kind.STRING) {
            return "\"" + literal + "\"";
        } else {
            return Objects.toString(literal);
        }
    }
}

package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.Arrays;
import java.util.Objects;

public class LiteralExpressionTree extends ExpressionTree {

    private Object literal;
    private Kind literalKind;

    public LiteralExpressionTree(final Object literal) {
        this(literal, Kind.resolveKind(literal));
    }

    public LiteralExpressionTree(final LiteralExpressionTreeBuilder builder) {
        super(builder);
        this.literal = builder.literal;
        this.literalKind = Kind.resolveKind(literal);
    }

    public LiteralExpressionTree(final Object literal,
                                 final Kind kind) {
        this.literal = literal;
        this.literalKind = kind;
    }

    public Kind getLiteralKind() {
        return literalKind;
    }

    public void setLiteralKind(final Kind literalKind) {
        this.literalKind = literalKind;
    }

    public Object getLiteral() {
        return literal;
    }

    public void setLiteral(final Object literal) {
        this.literal = literal;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitLiteralExpression(this, param);
    }

    public LiteralExpressionTree negate() {
        if (literal instanceof Integer integer) {
            final var negated = -integer;
            return builder()
                    .literal(negated)
                    .build();
        } else if (literal instanceof Long longValue) {
            final var negated = -longValue;
            return builder()
                    .literal(negated)
                    .build();
        } else {
            throw new TodoException();
        }
    }

    public enum Kind {
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

    @Override
    public String toString() {
        if (getLiteralKind() == Kind.STRING) {
            return "\"" + literal + "\"";
        } else {
            return Objects.toString(literal);
        }
    }

    public LiteralExpressionTreeBuilder builder() {
        return new LiteralExpressionTreeBuilder(this);
    }

    public static class LiteralExpressionTreeBuilder extends CExpressionBuilder<LiteralExpressionTree> {

        private Object literal;

        public LiteralExpressionTreeBuilder(final LiteralExpressionTree literalExpressionTree) {
            super(literalExpressionTree);
            this.literal = literalExpressionTree.getLiteral();
        }

        @Override
        public CExpressionBuilder<LiteralExpressionTree> self() {
            return this;
        }

        public LiteralExpressionTreeBuilder literal(final Object literal) {
            this.literal = literal;
            return this;
        }

        @Override
        public LiteralExpressionTree build() {
            return new LiteralExpressionTree(this);
        }
    }
}

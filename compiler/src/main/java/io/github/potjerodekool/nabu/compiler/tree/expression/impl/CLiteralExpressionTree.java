package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.LiteralExpressionTreeBuilder;

import java.util.Objects;

public class CLiteralExpressionTree extends CExpressionTree implements LiteralExpressionTree {

    private Object literal;
    private Kind literalKind;

    public CLiteralExpressionTree(final Object literal,
                                  final int lineNumber,
                                  final int charPositionInLine) {
        this(literal, Kind.resolveKind(literal), lineNumber, charPositionInLine);
    }

    public CLiteralExpressionTree(final LiteralExpressionTreeBuilder builder) {
        super(builder);
        this.literal = builder.getLiteral();
        this.literalKind = Kind.resolveKind(literal);
    }

    public CLiteralExpressionTree(final Object literal,
                                  final Kind kind,
                                  final int lineNumber,
                                  final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.literal = literal;
        this.literalKind = kind;
    }

    @Override
    public Kind getLiteralKind() {
        return literalKind;
    }

    @Override
    public void setLiteralKind(final Kind literalKind) {
        this.literalKind = literalKind;
    }

    @Override
    public Object getLiteral() {
        return literal;
    }

    @Override
    public void setLiteral(final Object literal) {
        this.literal = literal;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitLiteralExpression(this, param);
    }

    @Override
    public LiteralExpressionTree negate() {
        final Number negated = switch (literal) {
            case Byte b -> -b;
            case Double d -> -d;
            case Float f -> -f;
            case Integer integer -> -integer;
            case Long longValue -> -longValue;
            case Short s -> -s;
            case null, default -> throw new UnsupportedOperationException("can't negate literal");
        };

        return builder()
                .literal(negated)
                .build();
    }

    @Override
    public String toString() {
        if (getLiteralKind() == Kind.STRING) {
            return "\"" + literal + "\"";
        } else {
            return Objects.toString(literal);
        }
    }

    @Override
    public LiteralExpressionTreeBuilder builder() {
        return new LiteralExpressionTreeBuilder(this);
    }

}

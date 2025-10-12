package io.github.potjerodekool.nabu.compiler.backend.lower.widen;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.util.Types;

public class IntWidener {

    private final Types types;

    public IntWidener(final Types types) {
        this.types = types;
    }

    public ExpressionTree widenToLong(final ExpressionTree expressionTree) {
        if (expressionTree instanceof LiteralExpressionTree literalExpressionTree) {
            final var intLiteral = (Integer) literalExpressionTree.getLiteral();
            final var longLiteral = intLiteral.longValue();
            final var type = types.getPrimitiveType(TypeKind.LONG);

            return literalExpressionTree.builder()
                    .literal(longLiteral)
                    .type(type)
                    .build();
        } else {
            throw new UnsupportedOperationException();
        }
    }

}

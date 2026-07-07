package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.impl.CMemberReference;

import java.util.List;

/**
 * Member reference expresion.
 * For example:
 * Person::firstName
 */
public interface MemberReference extends ExpressionTree {
    String getName();

    List<IdentifierTree> getTypeArguments();

    ExpressionTree getExpression();

    ReferenceKind getMode();

    enum ReferenceKind {
        INVOKE,
        NEW
    }

    static MemberReference create(final ReferenceKind mode,
                                  final String name,
                                  final List<IdentifierTree> typeArguments,
                                  final ExpressionTree expression,
                                  final int lineNumber,
                                  final int columnNumber) {
        return new CMemberReference(
                mode, name, typeArguments, expression, lineNumber, columnNumber);
    }
}

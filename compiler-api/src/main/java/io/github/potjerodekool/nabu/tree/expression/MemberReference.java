package io.github.potjerodekool.nabu.tree.expression;

import java.util.List;

/**
 * Member reference expresion.
 * For example:
 * Person::firstName
 */
public interface MemberReference extends ExpressionTree {
    List<IdentifierTree> getTypeArguments();

    ExpressionTree getExpression();

    ReferenceKind getMode();

    enum ReferenceKind {
        INVOKE,
        NEW
    }
}

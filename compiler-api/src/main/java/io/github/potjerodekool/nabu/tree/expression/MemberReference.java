package io.github.potjerodekool.nabu.tree.expression;

import java.util.List;

public interface MemberReference extends ExpressionTree {
    List<IdentifierTree> getTypeArguments();

    ExpressionTree getExpression();

    ReferenceKind getMode();

    enum ReferenceKind {
        INVOKE,
        NEW
    }
}

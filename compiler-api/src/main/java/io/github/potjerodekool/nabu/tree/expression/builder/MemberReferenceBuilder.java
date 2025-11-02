package io.github.potjerodekool.nabu.tree.expression.builder;


import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.MemberReference;
import io.github.potjerodekool.nabu.tree.expression.impl.CMemberReference;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for Method references.
 */
public class MemberReferenceBuilder extends ExpressionBuilder<MemberReferenceBuilder> {

    private final List<IdentifierTree> typeArguments = new ArrayList<>();
    private IdentifierTree expression;
    private MemberReference.ReferenceKind mode;

    @Override
    public MemberReferenceBuilder self() {
        return this;
    }

    public List<IdentifierTree> getTypeArguments() {
        return typeArguments;
    }

    public MemberReferenceBuilder typeArguments(List<IdentifierTree> typeArguments) {
        this.typeArguments.clear();
        this.typeArguments.addAll(typeArguments);
        return this;
    }

    public IdentifierTree getExpression() {
        return expression;
    }

    public MemberReference.ReferenceKind getMode() {
        return mode;
    }

    public MemberReferenceBuilder mode(final MemberReference.ReferenceKind mode) {
        this.mode = mode;
        return this;
    }

    public MemberReferenceBuilder expression(final IdentifierTree expression) {
        this.expression = expression;
        return this;
    }

    @Override
    public MemberReference build() {
        return new CMemberReference(this);
    }
}

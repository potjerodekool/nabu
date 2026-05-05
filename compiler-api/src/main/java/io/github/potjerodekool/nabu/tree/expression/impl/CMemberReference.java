package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.MemberReference;
import io.github.potjerodekool.nabu.tree.expression.builder.MemberReferenceBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of MemberReference.
 */
public class CMemberReference extends CExpressionTree implements MemberReference {

    private final MemberReference.ReferenceKind mode;
    private final List<IdentifierTree> typeArguments = new ArrayList<>();
    private final String name;
    private final ExpressionTree expression;

    public CMemberReference(MemberReference.ReferenceKind mode,
                            final String name,
                            final List<IdentifierTree> typeArguments,
                            final ExpressionTree expression,
                            final int lineNumber,
                            final int columnNumber) {
        super(lineNumber, columnNumber);
        this.mode = mode;
        this.name = name;
        this.typeArguments.addAll(typeArguments);
        this.expression = expression;
    }

    public CMemberReference(final MemberReferenceBuilder builder) {
        super(builder);
        this.mode = builder.getMode();
        this.name = builder.getName();
        this.typeArguments.addAll(builder.getTypeArguments());
        this.expression = builder.getExpression();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public List<IdentifierTree> getTypeArguments() {
        return typeArguments;
    }

    @Override
    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public ReferenceKind getMode() {
        return mode;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitMemberReference(this, param);
    }
}

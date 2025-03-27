package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MemberReference;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.MemberReferenceBuilder;

import java.util.ArrayList;
import java.util.List;

public class CMemberReference extends CExpressionTree implements MemberReference {

    private final MemberReference.ReferenceKind mode;
    private final List<IdentifierTree> typeArguments = new ArrayList<>();
    private final IdentifierTree expression;

    public CMemberReference(MemberReference.ReferenceKind mode,
                            final List<IdentifierTree> typeArguments,
                            final IdentifierTree expression,
                            final int lineNumber,
                            final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.mode = mode;
        this.typeArguments.addAll(typeArguments);
        this.expression = expression;
    }

    public CMemberReference(final MemberReferenceBuilder builder) {
        super(builder);
        this.mode = builder.getMode();
        this.typeArguments.addAll(builder.getTypeArguments());
        this.expression = builder.getExpression();
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

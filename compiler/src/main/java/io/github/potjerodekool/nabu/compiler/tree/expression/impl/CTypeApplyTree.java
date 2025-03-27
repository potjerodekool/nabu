package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.TypeApplyTree;

import java.util.ArrayList;
import java.util.List;

public class CTypeApplyTree extends CExpressionTree implements TypeApplyTree {

    private final ExpressionTree clazz;

    private final List<ExpressionTree> typeParameters = new ArrayList<>();

    public CTypeApplyTree(final ExpressionTree clazz,
                          final List<? extends ExpressionTree> typeParameters,
                          final int lineNumber,
                          final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.clazz = clazz;
        this.typeParameters.addAll(typeParameters);
    }

    @Override
    public ExpressionTree getClazz() {
        return clazz;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeIdentifier(this, param);
    }

    public List<ExpressionTree> getTypeParameters() {
        return typeParameters;
    }
}

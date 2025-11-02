package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.TypeApplyTree;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of TypeApplyTree.
 */
public class CTypeApplyTree extends CExpressionTree implements TypeApplyTree {

    private final ExpressionTree clazz;

    private final List<Tree> typeParameters = new ArrayList<>();

    public CTypeApplyTree(final ExpressionTree clazz,
                          final List<? extends Tree> typeParameters,
                          final int lineNumber,
                          final int columnNumber) {
        super(lineNumber, columnNumber);
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

    public List<Tree> getTypeParameters() {
        return typeParameters;
    }

    @Override
    public void setType(final TypeMirror type) {
        super.setType(type);
    }
}

package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.TypeVariableTree;
import io.github.potjerodekool.nabu.tree.impl.CTree;

import java.util.List;

/**
 * Implementation of TypeVariableTree.
 */
public class CTypeVariableTree extends CTree implements TypeVariableTree {

    private final List<AnnotationTree> annotations;
    private final IdentifierTree identifier;

    public CTypeVariableTree(final List<AnnotationTree> annotations,
                             final IdentifierTree identifier,
                             final int lineNumber,
                             final int columnNumber) {
        super(lineNumber, columnNumber);
        this.annotations = annotations;
        this.identifier = identifier;
    }

    @Override
    public List<AnnotationTree> getAnnotations() {
        return annotations;
    }

    @Override
    public IdentifierTree getIdentifier() {
        return identifier;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeVariable(this, param);
    }
}

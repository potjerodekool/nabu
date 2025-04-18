package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.TypeVariableTree;
import io.github.potjerodekool.nabu.compiler.tree.impl.CTree;

import java.util.List;

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

package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;

import java.util.ArrayList;
import java.util.List;

public class CTypeParameterTree extends CTree implements TypeParameterTree {

    private final List<AnnotationTree> annotations;
    private final IdentifierTree identifier;
    private final List<ExpressionTree> typeBound = new ArrayList<>();

    public CTypeParameterTree(final List<AnnotationTree> annotations,
                              final IdentifierTree identifier,
                              final List<ExpressionTree> typeBound,
                              final int lineNumber,
                              final int columnNumber) {
        super(lineNumber, columnNumber);
        this.annotations = annotations;
        this.identifier = identifier;
        this.typeBound.addAll(typeBound);
    }

    public List<AnnotationTree> getAnnotations() {
        return annotations;
    }

    public IdentifierTree getIdentifier() {
        return identifier;
    }

    public List<ExpressionTree> getTypeBound() {
        return typeBound;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeParameter(this, param);
    }
}

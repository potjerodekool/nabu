package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;

import java.util.ArrayList;
import java.util.List;

public class TypeParameterTree extends Tree {

    private final List<AnnotationTree> annotations;
    private final IdentifierTree identifier;
    private final List<ExpressionTree> typeBound = new ArrayList<>();

    public TypeParameterTree(final List<AnnotationTree> annotations,
                             final IdentifierTree identifier,
                             final List<ExpressionTree> typeBound) {
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

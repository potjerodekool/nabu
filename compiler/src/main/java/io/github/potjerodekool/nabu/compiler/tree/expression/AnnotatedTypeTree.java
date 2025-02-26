package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.ArrayList;
import java.util.List;

public class AnnotatedTypeTree extends ExpressionTree {

    private final List<AnnotationTree> annotations = new ArrayList<>();

    private final ExpressionTree clazz;

    private final List<ExpressionTree> arguments;

    public AnnotatedTypeTree(final List<AnnotationTree> annotations,
                             final ExpressionTree clazz,
                             final List<ExpressionTree> arguments) {
        this.annotations.addAll(annotations);
        this.clazz = clazz;
        this.arguments = arguments;
    }

    public List<AnnotationTree> getAnnotations() {
        return annotations;
    }

    public ExpressionTree getClazz() {
        return clazz;
    }

    public List<ExpressionTree> getArguments() {
        return arguments;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitAnnotatedType(this, param);
    }
}

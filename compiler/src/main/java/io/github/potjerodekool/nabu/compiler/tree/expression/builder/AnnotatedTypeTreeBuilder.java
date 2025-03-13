package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotatedTypeTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CAnnotatedTypeTree;

import java.util.ArrayList;
import java.util.List;

public class AnnotatedTypeTreeBuilder extends ExpressionBuilder<AnnotatedTypeTree> {

    private final List<AnnotationTree> annotations = new ArrayList<>();

    private ExpressionTree clazz;

    private final List<ExpressionTree> arguments = new ArrayList<>();

    public AnnotatedTypeTreeBuilder(final AnnotatedTypeTree annotatedTypeTree) {
        super(annotatedTypeTree);
        this.annotations.addAll(annotatedTypeTree.getAnnotations());
        this.clazz = annotatedTypeTree.getClazz();
        this.arguments.addAll(annotatedTypeTree.getArguments());
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
    public AnnotatedTypeTreeBuilder self() {
        return this;
    }

    public AnnotatedTypeTreeBuilder clazz(final ExpressionTree clazz) {
        this.clazz = clazz;
        return this;
    }

    @Override
    public CAnnotatedTypeTree build() {
        return new CAnnotatedTypeTree(this);
    }
}
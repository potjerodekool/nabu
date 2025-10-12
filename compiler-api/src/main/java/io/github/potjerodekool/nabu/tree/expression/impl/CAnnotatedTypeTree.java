package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.AnnotatedTypeTree;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.builder.AnnotatedTypeTreeBuilder;

import java.util.ArrayList;
import java.util.List;

public class CAnnotatedTypeTree extends CExpressionTree implements AnnotatedTypeTree {

    private final List<AnnotationTree> annotations = new ArrayList<>();

    private final ExpressionTree clazz;

    private final List<ExpressionTree> arguments = new ArrayList<>();

    public CAnnotatedTypeTree(final List<AnnotationTree> annotations,
                              final ExpressionTree clazz,
                              final List<ExpressionTree> arguments,
                              final int lineNumber,
                              final int columnNumber) {
        super(lineNumber, columnNumber);
        this.annotations.addAll(annotations);
        this.clazz = clazz;
        this.arguments.addAll(arguments);
    }

    public CAnnotatedTypeTree(final AnnotatedTypeTreeBuilder annotatedTypeTreeBuilder) {
        super(annotatedTypeTreeBuilder);
        this.annotations.addAll(annotatedTypeTreeBuilder.getAnnotations());
        this.clazz = annotatedTypeTreeBuilder.getClazz();
        this.arguments.addAll(annotatedTypeTreeBuilder.getArguments());
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

    @Override
    public AnnotatedTypeTreeBuilder builder() {
        return new AnnotatedTypeTreeBuilder(this);
    }

}

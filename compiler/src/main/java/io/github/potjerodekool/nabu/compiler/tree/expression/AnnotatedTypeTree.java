package io.github.potjerodekool.nabu.compiler.tree.expression;


import io.github.potjerodekool.nabu.compiler.tree.expression.builder.AnnotatedTypeTreeBuilder;

import java.util.List;

public interface AnnotatedTypeTree extends ExpressionTree {

    List<AnnotationTree> getAnnotations();

    ExpressionTree getClazz();

    List<ExpressionTree> getArguments();

    AnnotatedTypeTreeBuilder builder();

}

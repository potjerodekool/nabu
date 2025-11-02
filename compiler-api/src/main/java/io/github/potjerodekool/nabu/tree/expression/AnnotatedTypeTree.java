package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.builder.AnnotatedTypeTreeBuilder;

import java.util.List;

/**
 * Annotated type.
 * For example:
 * <p> </p>
 * {@code @}Column(name = "firstName")
 * private String firstName;
 *
 */
public interface AnnotatedTypeTree extends ExpressionTree {

    List<AnnotationTree> getAnnotations();

    ExpressionTree getClazz();

    List<ExpressionTree> getArguments();

    AnnotatedTypeTreeBuilder builder();

}

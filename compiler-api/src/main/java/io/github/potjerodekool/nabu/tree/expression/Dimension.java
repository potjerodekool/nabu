package io.github.potjerodekool.nabu.tree.expression;

import java.util.List;

/**
 * Dimension of an array.
 */
public interface Dimension extends ExpressionTree {
    List<AnnotationTree> getAnnotations();
}

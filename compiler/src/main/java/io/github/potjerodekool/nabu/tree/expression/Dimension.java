package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.impl.CDimension;

import java.util.List;

/**
 * Dimension of an array.
 */
public interface Dimension extends ExpressionTree {
    List<AnnotationTree> getAnnotations();

    static Dimension create(final List<AnnotationTree> annotations,
                            final int lineNumber,
                            final int columnNumber) {
        return new CDimension(annotations, lineNumber, columnNumber);
    }
}

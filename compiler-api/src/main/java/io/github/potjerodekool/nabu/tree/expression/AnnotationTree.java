package io.github.potjerodekool.nabu.tree.expression;

import java.util.List;

/**
 * Annotation
 */
public interface AnnotationTree extends ExpressionTree {

    /**
     * @return Returns the annotation name
     */
    IdentifierTree getName();

    /**
     * @return Returns the arguments.
     */
    List<ExpressionTree> getArguments();

}

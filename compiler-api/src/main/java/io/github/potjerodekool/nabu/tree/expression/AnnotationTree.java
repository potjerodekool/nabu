package io.github.potjerodekool.nabu.tree.expression;

import java.util.List;

/**
 * Annotation
 */
public interface AnnotationTree extends ExpressionTree {

    /**
     * Returns the annotation name
     * @return Returns the annotation name
     */
    IdentifierTree getName();

    /**
     * Returns the arguments.
     * @return Returns the arguments.
     */
    List<ExpressionTree> getArguments();

    /**
     * Return the argument value.
     * @param name A name
     * @return Return the argument value
     */
    ExpressionTree getArgumentValue(String name);
}

package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;

import java.util.List;

/**
 * Represents a type parameter.
 * <p> </p>
 * class Animal&lt;T extends Animal&gt; {
 * <p> </p>
 * }
 * <p> </p>
 * class Calculator {
 *     &lt;T&gt; T add(T first, T second) {
 *     ...
 *     <p> </p>
 *     }
 * }
 */
public interface TypeParameterTree extends Tree {

    List<AnnotationTree> getAnnotations();

    IdentifierTree getIdentifier();

    List<ExpressionTree> getTypeBound();
}

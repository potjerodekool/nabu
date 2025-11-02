package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tree;

import java.util.List;

/**
 * Type variable
 */
public interface TypeVariableTree extends Tree {

    List<AnnotationTree> getAnnotations();

    IdentifierTree getIdentifier();
}

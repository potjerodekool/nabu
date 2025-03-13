package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

import java.util.List;

public interface TypeVariableTree extends Tree {

    List<AnnotationTree> getAnnotations();

    IdentifierTree getIdentifier();
}

package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;

import java.util.List;

public interface TypeParameterTree extends Tree {

    List<AnnotationTree> getAnnotations();

    IdentifierTree getIdentifier();

    List<ExpressionTree> getTypeBound();
}

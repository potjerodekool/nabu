package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;

import java.util.List;

public interface TypeParameterTree extends Tree {

    List<AnnotationTree> getAnnotations();

    IdentifierTree getIdentifier();

    List<ExpressionTree> getTypeBound();
}

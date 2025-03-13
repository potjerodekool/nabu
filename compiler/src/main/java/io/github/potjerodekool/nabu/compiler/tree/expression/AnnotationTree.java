package io.github.potjerodekool.nabu.compiler.tree.expression;

import java.util.List;

public interface AnnotationTree extends ExpressionTree {

    IdentifierTree getName();

    List<ExpressionTree> getArguments();

}

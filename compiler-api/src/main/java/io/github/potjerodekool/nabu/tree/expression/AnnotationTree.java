package io.github.potjerodekool.nabu.tree.expression;

import java.util.List;

public interface AnnotationTree extends ExpressionTree {

    IdentifierTree getName();

    List<ExpressionTree> getArguments();

}

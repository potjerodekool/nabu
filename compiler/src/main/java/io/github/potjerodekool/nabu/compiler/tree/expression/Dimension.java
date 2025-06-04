package io.github.potjerodekool.nabu.compiler.tree.expression;

import java.util.List;

public interface Dimension extends ExpressionTree {
    List<AnnotationTree> getAnnotations();
}

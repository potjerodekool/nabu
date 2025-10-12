package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public interface ExpressionConverter {

    ExpressionTree convert(final ExpressionTree left,
                           final ExpressionTree right);
}

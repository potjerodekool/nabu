package io.github.potjerodekool.nabu.compiler.lang.helper;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.type.BoundKind;

public record WildcardBound(BoundKind kind, ExpressionTree expression) {
}

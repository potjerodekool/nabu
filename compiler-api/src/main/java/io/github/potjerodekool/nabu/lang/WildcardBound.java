package io.github.potjerodekool.nabu.lang;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.type.BoundKind;

public record WildcardBound(BoundKind kind, ExpressionTree expression) {
}

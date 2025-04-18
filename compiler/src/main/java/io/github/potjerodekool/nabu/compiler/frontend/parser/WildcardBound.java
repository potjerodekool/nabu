package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.type.BoundKind;

public record WildcardBound(BoundKind kind, ExpressionTree expression) {
}

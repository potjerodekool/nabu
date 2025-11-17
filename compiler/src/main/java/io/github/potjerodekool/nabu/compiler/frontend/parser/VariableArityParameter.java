package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;

public record VariableArityParameter(Modifiers modifiers,
                                     ExpressionTree type,
                                     IdentifierTree name) {
}

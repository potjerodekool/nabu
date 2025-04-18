package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;

import java.util.List;

public record MethodDeclarator(VariableDeclaratorTree receiverParameter,
                               String name,
                               List<VariableDeclaratorTree> parameters) {
}

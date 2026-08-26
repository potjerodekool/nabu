package io.github.potjerodekool.nabu.lang;

import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

import java.util.List;

public record MethodDeclarator(VariableDeclaratorTree receiverParameter,
                               String name,
                               List<VariableDeclaratorTree> parameters) {
}

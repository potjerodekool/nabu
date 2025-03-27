package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;

public interface CatchTree extends Tree {

    VariableDeclaratorTree getVariable();
    BlockStatementTree getBody();
}

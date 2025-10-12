package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

public interface CatchTree extends Tree {

    VariableDeclaratorTree getVariable();
    BlockStatementTree getBody();
}

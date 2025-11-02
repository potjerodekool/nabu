package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.impl.CCatchTree;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

/**
 * A catch tree.
 * <p> </p>
 * catch (Exception e)
 */
public sealed interface CatchTree extends Tree permits CCatchTree {

    VariableDeclaratorTree getVariable();
    BlockStatementTree getBody();
}

package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.Tree;

/**
 * Continue statement.
 */
public interface ContinueStatement extends StatementTree{

    /**
     * @return Return the target which may be null.
     */
    Tree getTarget();
}

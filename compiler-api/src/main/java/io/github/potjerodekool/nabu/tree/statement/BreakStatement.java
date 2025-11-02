package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.Tree;


/**
 * Break statement.
 */
public interface BreakStatement extends StatementTree {

    /**
     * @return Returns the break target which may be null
     */
    Tree getTarget();
}

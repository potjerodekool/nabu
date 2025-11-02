package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.CatchTree;
import io.github.potjerodekool.nabu.tree.Tree;

import java.util.List;

/**
 * Try statement
 */
public interface TryStatementTree extends StatementTree {

    /**
     * @return Returns the body.
     */
    BlockStatementTree getBody();

    /**
     * @return Returns the caches.
     */
    List<CatchTree> getCatchers();

    /**
     * @return Returns the finalizer.
     */
    BlockStatementTree getFinalizer();

    /**
     * @return Returns the resources.
     */
    List<Tree> getResources();
}

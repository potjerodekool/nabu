package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.CatchTree;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.statement.impl.CTryStatementTree;

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

    static TryStatementTree create(final BlockStatementTree body,
                                   final List<CatchTree> catchers,
                                   final BlockStatementTree finalizer,
                                   final List<Tree> resources,
                                   final int lineNumber,
                                   final int columnNumber) {
        return new CTryStatementTree(body, catchers, finalizer, resources, lineNumber, columnNumber);
    }
}

package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.statement.impl.CBreakStatement;


/**
 * Break statement.
 */
public interface BreakStatement extends StatementTree {

    /**
     * @return Returns the break target which may be null
     */
    Tree getTarget();

    static BreakStatement create(final Tree target,
                                 final int lineNumber,
                                 final int columnNumber) {
        return new CBreakStatement(target, lineNumber, columnNumber);
    }
}

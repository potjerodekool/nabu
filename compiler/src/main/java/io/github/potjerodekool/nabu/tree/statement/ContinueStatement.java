package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.statement.impl.CContinueStatement;

/**
 * Continue statement.
 */
public interface ContinueStatement extends StatementTree{

    /**
     * @return Return the target which may be null.
     */
    Tree getTarget();


    static ContinueStatement create(final Tree target,
                                    final int lineNumber,
                                    final int columnNumber) {
        return new CContinueStatement(target, lineNumber, columnNumber);
    }
}

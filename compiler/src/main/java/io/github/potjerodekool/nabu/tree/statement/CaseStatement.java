package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.CaseLabel;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.statement.impl.CCaseStatement;

import java.util.List;

/**
 * Case statement.
 */
public interface CaseStatement extends StatementTree {

    /**
     * @return Returns the labels.
     */
    List<CaseLabel> getLabels();

    /**
     * @return Return the case body.
     */
    Tree getBody();

    /**
     * @return Return the kind of the case.
     */
    CaseKind getCaseKind();

    static CaseStatement create(final CaseKind caseKind,
                                final List<CaseLabel> labels,
                                final Tree body,
                                final int lineNumber,
                                final int columnNumber) {
        return new CCaseStatement(caseKind, labels, body, lineNumber, columnNumber);
    }

    enum CaseKind {
        RULE,
        STATEMENT
    }

}

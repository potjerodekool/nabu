package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.CaseLabel;
import io.github.potjerodekool.nabu.tree.Tree;

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

    enum CaseKind {
        RULE,
        STATEMENT
    }

}

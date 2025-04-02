package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.CaseLabel;
import io.github.potjerodekool.nabu.compiler.tree.Tree;

import java.util.List;

public interface CaseStatement extends StatementTree {

    List<CaseLabel> getLabels();

    Tree getBody();

    CaseKind getCaseKind();

    enum CaseKind {
        RULE,
        STATEMENT
    }

}

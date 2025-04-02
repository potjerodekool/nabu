package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

import java.util.List;

public interface SwitchStatement extends StatementTree {
    ExpressionTree getSelector();

    List<CaseStatement> getCases();
}

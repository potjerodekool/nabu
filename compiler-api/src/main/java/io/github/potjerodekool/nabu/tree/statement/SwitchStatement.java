package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.SwitchStatementBuilder;

import java.util.List;

public interface SwitchStatement extends StatementTree {
    ExpressionTree getSelector();

    List<CaseStatement> getCases();

    SwitchStatementBuilder builder();
}

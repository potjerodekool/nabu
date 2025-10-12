package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public interface AssertStatement extends StatementTree {

    ExpressionTree getCondition();

    ExpressionTree getDetail();

}

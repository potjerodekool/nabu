package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public interface YieldStatement extends StatementTree {

    ExpressionTree getExpression();
}

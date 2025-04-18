package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public interface YieldStatement extends StatementTree {

    ExpressionTree getExpression();
}

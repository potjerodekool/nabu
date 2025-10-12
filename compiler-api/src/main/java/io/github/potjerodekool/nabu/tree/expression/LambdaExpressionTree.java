package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.builder.LambdaExpressionTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.List;

public interface LambdaExpressionTree extends ExpressionTree {

    List<VariableDeclaratorTree> getVariables();

    StatementTree getBody();

    ExecutableType getLambdaMethodType();

    LambdaExpressionTreeBuilder builder();

    void setLambdaMethodType(ExecutableType type);

    LambdaExpressionTree body(StatementTree body);
}
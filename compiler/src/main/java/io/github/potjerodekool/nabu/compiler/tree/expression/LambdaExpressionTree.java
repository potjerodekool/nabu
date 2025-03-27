package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.expression.builder.LambdaExpressionTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;

import java.util.List;

public interface LambdaExpressionTree extends ExpressionTree {

    List<VariableDeclaratorTree> getVariables();

    StatementTree getBody();

    ExecutableType getLambdaMethodType();

    LambdaExpressionTreeBuilder builder();

    void setLambdaMethodType(ExecutableType type);

    LambdaExpressionTree body(StatementTree body);
}
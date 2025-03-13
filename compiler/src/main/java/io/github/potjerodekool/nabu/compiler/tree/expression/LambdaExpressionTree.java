package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.expression.builder.LambdaExpressionTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CLambdaExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclarator;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;

import java.util.List;

public interface LambdaExpressionTree extends ExpressionTree {

    List<VariableDeclarator> getVariables();

    Statement getBody();

    ExecutableType getLambdaMethodType();

    LambdaExpressionTreeBuilder builder();

    void setLambdaMethodType(ExecutableType type);

    LambdaExpressionTree body(Statement body);
}
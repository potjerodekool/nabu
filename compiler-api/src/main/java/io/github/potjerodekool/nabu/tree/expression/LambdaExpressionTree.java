package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.expression.builder.LambdaExpressionTreeBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CLambdaExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.List;

/**
 * Lambda expression.
 */
public interface LambdaExpressionTree extends ExpressionTree {

    LambdaExpressionTree.ParameterKind getParameterKind();

    List<VariableDeclaratorTree> getVariables();

    Tree getBody();

    ExecutableType getLambdaMethodType();

    LambdaExpressionTreeBuilder builder();

    void setLambdaMethodType(ExecutableType type);

    LambdaExpressionTree body(StatementTree body);


    enum ParameterKind {
        IMPLICIT,
        EXPLICIT
    }
}
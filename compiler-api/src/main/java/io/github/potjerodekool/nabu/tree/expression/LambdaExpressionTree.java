package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.expression.builder.LambdaExpressionTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.List;

/**
 * Lambda expression.
 */
public interface LambdaExpressionTree extends ExpressionTree {

    ParameterKind getParameterKind();

    List<Tree> getVariables();

    Tree getBody();

    ExecutableType getLambdaMethodType();

    LambdaExpressionTreeBuilder builder();

    void setLambdaMethodType(ExecutableType type);

    LambdaExpressionTree body(StatementTree body);

    /**
     * The receiver of a bound method reference ({@code expr::m}) this lambda
     * was made from, or null. It is evaluated once, where the lambda is
     * created, and captured under {@link #getBoundReceiverName()} (JLS
     * 15.13.3); the body refers to it by that name.
     */
    default ExpressionTree getBoundReceiver() {
        return null;
    }

    /** The name the body uses for the bound receiver, or null. */
    default String getBoundReceiverName() {
        return null;
    }

    default void setBoundReceiver(ExpressionTree receiver, String name) {
        throw new UnsupportedOperationException();
    }


    enum ParameterKind {
        IMPLICIT,
        EXPLICIT
    }
}
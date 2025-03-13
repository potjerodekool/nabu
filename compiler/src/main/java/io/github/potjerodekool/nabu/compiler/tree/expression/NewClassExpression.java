package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;

import java.util.List;

public interface NewClassExpression extends ExpressionTree {

    ExpressionTree getName();

    List<ExpressionTree> getArguments();

    BlockStatement getBody();
}

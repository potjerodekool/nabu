package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.builder.MethodInvocationTreeBuilder;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.ArrayList;
import java.util.List;

public interface MethodInvocationTree extends ExpressionTree {

    ExpressionTree getMethodSelector();

    List<ExpressionTree> getArguments();

    List<IdentifierTree> getTypeArguments();

    ExecutableType getMethodType();

    void setMethodType(ExecutableType resolvedMethodType);

    void setArguments(ArrayList<ExpressionTree> newArgs);

    MethodInvocationTreeBuilder builder();
}

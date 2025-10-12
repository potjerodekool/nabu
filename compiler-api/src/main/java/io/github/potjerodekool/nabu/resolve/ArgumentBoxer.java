package io.github.potjerodekool.nabu.resolve;

import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;

public interface ArgumentBoxer {

    void boxArguments(MethodInvocationTree methodInvocation);
}


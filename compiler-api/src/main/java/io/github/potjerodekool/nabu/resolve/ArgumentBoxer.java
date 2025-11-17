package io.github.potjerodekool.nabu.resolve;

import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;

/**
 * A Utility to box and unbox arguments of a method invocation.
 */
public interface ArgumentBoxer {

    /**
     * Box and unbox arguments.
     * @param methodInvocation A method invocation.
     */
    void boxArguments(MethodInvocationTree methodInvocation);
}


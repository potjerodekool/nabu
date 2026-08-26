package io.github.potjerodekool.nabu.resolve.method;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.Optional;

/**
 * Utility to resolve method invocations.
 */
public interface MethodResolver {

    /**
     * @param methodInvocation A method invocation.
     * @param currentElement   The current element to search on.
     * @param scope            The current scope.
     * @return Returns the optional resolved method.
     */
    Optional<ExecutableType> resolveMethod(MethodInvocationTree methodInvocation,
                                           Element currentElement,
                                           Scope scope);
    /**
     * New experialmental method to resolve methods.
     * @param methodInvocationTree A method invocation.
     * @param scope  The current scope.
     * @return Returns the resolved method or null.
     */
    Optional<ExecutableType> resolveMethod(MethodInvocationTree methodInvocationTree,
                                 Scope scope);
}
package io.github.potjerodekool.nabu.compiler.resolve.method;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.List;
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

    /**
     * Resolves the constructor of a class that a {@code new} expression calls.
     *
     * @param type      The class that is instantiated.
     * @param arguments The arguments of the constructor call.
     * @return Returns the resolved constructor or empty.
     */
    Optional<ExecutableType> resolveConstructor(DeclaredType type, List<ExpressionTree> arguments);
}

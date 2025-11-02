package io.github.potjerodekool.nabu.resolve.method;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.*;

/**
 * Utility to resolve method invocations.
 */
public interface MethodResolver {

    /**
     * @param methodInvocation A method invocation.
     * @return Returns the optional resolved method.
     */
    Optional<ExecutableType> resolveMethod(final MethodInvocationTree methodInvocation);

    /**
     * @param methodInvocation A method invocation.
     * @param currentElement The current element to search on.
     * @param scope The current scope.
     * @return Returns the optional resolved method.
     */
    Optional<ExecutableType> resolveMethod(final MethodInvocationTree methodInvocation,
                                           final Element currentElement,
                                           final Scope scope);


    /**
     * @param methodType A method type.
     * @param typeArguments Type arguments.
     * @param argumentTypes Argument types.
     * @return Return the transformed method type.
     */
    ExecutableType transform(final ExecutableType methodType,
                             final List<?extends TypeMirror> typeArguments,
                             final List<TypeMirror> argumentTypes);


}
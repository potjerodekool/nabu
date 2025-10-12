package io.github.potjerodekool.nabu.resolve.method;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.*;

public interface MethodResolver {

    Optional<ExecutableType> resolveMethod(final MethodInvocationTree methodInvocation);

    Optional<ExecutableType> resolveMethod(final MethodInvocationTree methodInvocation,
                                           final Element currentElement,
                                           final Scope scope);


    ExecutableType transform(final ExecutableType methodType,
                             final List<TypeMirror> typeArguments,
                             final List<TypeMirror> argumentTypes);


}
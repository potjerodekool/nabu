package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.CExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CFieldAccessExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CIdent;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocation;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableMethodType;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public class MethodResolver {

    private final Types types;

    public MethodResolver(final Types types) {
        this.types = types;
    }

    public MutableMethodType resolveMethod(final MethodInvocation methodInvocation) {
        final var target = methodInvocation.getTarget();
        final var targetSymbol = target.getSymbol();

        final ClassType targetType;

        if (targetSymbol instanceof VariableElement variableElement) {
            targetType = TypeUtils.INSTANCE.asClassType(variableElement.getVariableType());
        } else if (targetSymbol instanceof ClassSymbol) {
            targetType = TypeUtils.INSTANCE.asClassType(targetSymbol.asType());
        } else {
            targetType = TypeUtils.INSTANCE.asClassType(target.getType());
        }

        final var name = (CIdent) methodInvocation.getName();
        final var argumentTypes = methodInvocation.getArguments().stream()
                .map(this::resolveType)
                .toList();

        return resolveMethod(
                targetType,
                name.getName(),
                argumentTypes
        );
    }

    private TypeMirror resolveType(final CExpression expression) {
        if (expression instanceof CFieldAccessExpression fieldAccessExpression) {
            return resolveType(fieldAccessExpression.getField());
        }

        var type = expression.getType();

        if (type != null) {
            return type;
        }

        final var symbol = (VariableElement) expression.getSymbol();

        if (symbol == null) {
            throw new NullPointerException();
        }

        return symbol.getVariableType();
    }

    public MutableMethodType resolveMethod(final ClassType targetType,
                                           final String methodName,
                                           final List<TypeMirror> argumentTypes) {
        if ("super".equals(methodName)) {
            var clazz = (ClassSymbol) targetType.asElement();
            final var searchType = (ClassType) clazz.getSuperType();
            return doResolveMethod(
                    searchType,
                    "this",
                    argumentTypes
            );
        } else {
            final var methodType = doResolveMethod(targetType, methodName, argumentTypes);

            if (methodType == null) {
                return null;
            }

            return new MutableMethodType(
                    (TypeElement) targetType.asElement(),
                    methodType.getMethodSymbol(),
                    methodType.getReturnType(),
                    methodType.getArgumentTypes()
            );
        }
    }

    private MutableMethodType doResolveMethod(final ClassType type,
                                              final String methodName,
                                              final List<TypeMirror> argumentTypes) {
        final var clazz = (ClassSymbol) type.asElement();

        final var methods = clazz.getEnclosedElements().stream()
                .filter(element -> methodFilter(element, methodName))
                .map(element -> (MethodSymbol) element)
                .filter(method -> match(method, argumentTypes))
                .toList();

        if (methods.size() == 1) {
            return methods.getFirst().getMethodType();
        } else if (methods.isEmpty()) {
            final var interfaceMethodOptional = clazz.getInterfaces().stream()
                    .map(interfaceType -> doResolveMethod(
                            (ClassType) interfaceType,
                            methodName,
                            argumentTypes
                    ))
                    .filter(Objects::nonNull)
                    .findFirst();

            if (interfaceMethodOptional.isPresent()) {
                return interfaceMethodOptional.get();
            }

            final var superType = (ClassType) clazz.getSuperType();

            if (superType == null) {
                return null;
            } else {
                return doResolveMethod(
                        superType,
                        methodName,
                        argumentTypes
                );
            }
        } else {
            return null;
        }
    }

    private boolean methodFilter(final Element element,
                                 final String methodName) {
        if ("this".equals(methodName)) {
            return element.getKind() == ElementKind.CONSTRUCTOR;
        } else {
            return element.getKind() == ElementKind.METHOD
                    && methodName.equals(element.getSimpleName());
        }
    }

    private boolean match(final MethodSymbol method,
                          final List<TypeMirror> argumentTypes) {
        final var methodType = method.getMethodType();
        final var argTypes = methodType.getArgumentTypes();

        if (argTypes.size() != argumentTypes.size()) {
            return false;
        }

        return IntStream.range(0, argTypes.size())
                .allMatch(index -> types.isAssignable(argumentTypes.get(index), argTypes.get(index)));
    }
}

package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.util.Types;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MethodResolver {

    private final Types types;

    public MethodResolver(final Types types) {
        this.types = types;
    }

    public ExecutableType resolveMethod(final MethodInvocationTree methodInvocation,
                                        final Element currentElement) {
        return resolveMethod(methodInvocation, currentElement, true);
    }

    private ExecutableType resolveMethod(final MethodInvocationTree methodInvocation,
                                         final Element currentElement,
                                         final boolean resolveDescriptor) {
        /*
         * TODO rename to onlyStaticCalls.
         * If true then only resolve static calls else both static and non static calls.
         */
        final boolean isStaticCall;
        final var target = methodInvocation.getTarget();

        final DeclaredType targetType;

        if (target == null) {
            if (currentElement instanceof ExecutableElement executableElement) {
                isStaticCall = executableElement.isStatic();
                final var clazz = (TypeElement) executableElement.getEnclosingElement();
                targetType = (DeclaredType) clazz.asType();
            } else {
                isStaticCall = false;
                final var clazz = (TypeElement) currentElement;
                targetType = (DeclaredType) clazz.asType();
            }
        } else {
            final var targetSymbol = target.getSymbol();

            if (targetSymbol instanceof VariableElement variableElement) {
                targetType = asClassType(variableElement.asType());
                isStaticCall = false;
            } else if (targetSymbol instanceof TypeElement) {
                targetType = asClassType(targetSymbol.asType());
                isStaticCall = true;
            } else {
                targetType = asClassType(target.getType());
                isStaticCall = false;
            }
        }

        final var name = (IdentifierTree) methodInvocation.getName();
        final var argumentTypes = methodInvocation.getArguments().stream()
                .map(this::resolveType)
                .toList();

        final var typeArguments = methodInvocation.getTypeArguments().stream()
                .map(this::resolveType)
                .toList();

        final var methodType = resolveMethod(
                targetType,
                name.getName(),
                typeArguments,
                argumentTypes,
                isStaticCall
        );

        if (methodType == null && resolveDescriptor) {
            resolveMethodDescriptor(methodInvocation);
            resolveMethod(methodInvocation, null, false);
        }

        return methodType;
    }

    public DeclaredType asClassType(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            return declaredType;
        } else if (typeMirror instanceof io.github.potjerodekool.nabu.compiler.type.VariableType variableType) {
            return asClassType(variableType.getInterferedType());
        } else {
            return types.getErrorType(typeMirror.getClassName());
        }
    }

    private void resolveMethodDescriptor(final MethodInvocationTree methodInvocationTree) {
        final var name = methodInvocationTree.getName();
        final var args = methodInvocationTree.getArguments().stream()
                .map(this::resolveType)
                .map(this::typeToString)
                .collect(Collectors.joining(",", "(", ")"));

        final var mt = name + args;
        System.out.println("method not found " + mt);
    }

    private String typeToString(final TypeMirror typeMirror) {
        final var printer = new TypePrinter();
        typeMirror.accept(printer, null);
        return printer.getText();
    }

    private TypeMirror resolveType(final ExpressionTree expression) {
        if (expression instanceof FieldAccessExpressionTree fieldAccessExpression) {
            return resolveType(fieldAccessExpression.getField());
        } else if (expression instanceof MethodInvocationTree methodInvocationTree) {
            return methodInvocationTree.getMethodType().getReturnType();
        }

        var type = expression.getType();

        if (type != null) {
            return type;
        }

        final var symbol = expression.getSymbol();

        if (symbol == null) {
            throw new NullPointerException();
        }

        return symbol.asType();
    }

    private ExecutableType resolveMethod(final DeclaredType targetType,
                                         final String methodName,
                                         final List<TypeMirror> typeArguments,
                                         final List<TypeMirror> argumentTypes,
                                         final boolean isStaticCall) {
        if ("super".equals(methodName)) {
            var clazz = (TypeElement) targetType.asElement();
            final var searchType = (DeclaredType) clazz.getSuperclass();
            return doResolveMethod(
                    searchType,
                    "this",
                    typeArguments,
                    argumentTypes,
                    isStaticCall);
        } else {
            return doResolveMethod(
                    targetType,
                    methodName,
                    typeArguments,
                    argumentTypes,
                    isStaticCall
            );
        }
    }

    private ExecutableType doResolveMethod(final DeclaredType type,
                                           final String methodName,
                                           final List<TypeMirror> typeArguments,
                                           final List<TypeMirror> argumentTypes, final boolean isStaticCall) {
        final ExecutableType methodType;

        final var clazz = (TypeElement) type.asElement();
        final List<ExecutableElement> methods;

        if (Constants.THIS.equals(methodName)) {
            methods = ElementFilter.constructors(clazz);
        } else {
            methods = ElementFilter.methods(clazz).stream()
                    .filter(element -> methodFilter(element, methodName, isStaticCall))
                    .toList();
        }

        final var methodTypes = methods.stream()
                .map(method -> (ExecutableType) types.asMemberOf(type, method))
                .map(mt -> transform(mt, typeArguments, argumentTypes))
                .filter(method -> match(method, argumentTypes))
                .toList();

        if (methodTypes.size() == 1) {
            methodType = methodTypes.getFirst();
        } else if (methodTypes.isEmpty()) {
            final var interfaceMethodOptional = clazz.getInterfaces().stream()
                    .map(interfaceType -> doResolveMethod(
                            (DeclaredType) interfaceType,
                            methodName,
                            typeArguments,
                            argumentTypes,
                            isStaticCall))
                    .filter(Objects::nonNull)
                    .findFirst();

            if (interfaceMethodOptional.isPresent()) {
                methodType = interfaceMethodOptional.get();
            } else {
                final var superType = (DeclaredType) clazz.getSuperclass();

                if (superType == null) {
                    methodType = null;
                } else {
                    methodType = doResolveMethod(
                            superType,
                            methodName,
                            typeArguments,
                            argumentTypes,
                            isStaticCall);
                }
            }
        } else {
            methodType = null;
        }
        return methodType;
    }


    public ExecutableType transform(final ExecutableType methodType,
                                    final List<TypeMirror> typeArguments,
                                    final List<TypeMirror> argumentTypes) {
        final var typeMapFiller = new TypeMapFiller();
        final var map = typeMapFiller.getMap();
        final var methodArgumentTypes = methodType.getParameterTypes();

        for (var i = 0; i < methodArgumentTypes.size(); i++) {
            final var methodArgType = methodArgumentTypes.get(i);

            if (argumentTypes.size() > i) {
                final var argType = argumentTypes.get(i);
                argType.accept(typeMapFiller, methodArgType);
            }
        }

        if (typeArguments.size() == methodType.getTypeVariables().size()) {
            for (var i = 0; i < typeArguments.size(); i++) {
                final var typeArg = typeArguments.get(i);
                final var typeVar = methodType.getTypeVariables().get(i);
                typeArg.accept(typeMapFiller, typeVar);
            }
        }

        final var filler3 = new Filer3(types);
        return (ExecutableType) methodType.accept(filler3, map);
    }

    private boolean methodFilter(final ExecutableElement method,
                                 final String methodName,
                                 final boolean isStaticCall) {
        if (isStaticCall && !method.isStatic()) {
            return false;
        }

        if ("this".equals(methodName)) {
            return method.getKind() == ElementKind.CONSTRUCTOR;
        } else {
            return method.getKind() == ElementKind.METHOD
                    && methodName.equals(method.getSimpleName());
        }
    }

    private boolean match(final ExecutableType methodType,
                          final List<TypeMirror> argumentTypes) {
        final var argTypes = methodType.getParameterTypes();

        if (argTypes.size() != argumentTypes.size()) {
            return false;
        }

        return IntStream.range(0, argTypes.size())
                .allMatch(index -> types.isAssignable(argumentTypes.get(index), argTypes.get(index)));
    }
}

class TypeMapFiller implements TypeVisitor<Void, TypeMirror> {

    private final HashMap<String, TypeMirror> map = new HashMap<>();

    public HashMap<String, TypeMirror> getMap() {
        return map;
    }

    @Override
    public Void visitUnknownType(final TypeMirror typeMirror, final TypeMirror param) {
        return null;
    }

    @Override
    public Void visitDeclaredType(final DeclaredType declaredType, final TypeMirror otherType) {
        final var typeArgs = declaredType.getTypeArguments();

        switch (otherType) {
            case DeclaredType otherDeclaredType -> {
                final var typeArgs2 = otherDeclaredType.getTypeArguments();
                if (typeArgs2 != null && typeArgs2.size() == typeArgs.size()) {
                    for (var i = 0; i < typeArgs.size(); i++) {
                        final var typeArg = typeArgs.get(i);
                        final var typeArg2 = typeArgs2.get(i);
                        typeArg.accept(this, typeArg2);
                    }
                }
            }
            case WildcardType wildcardType -> {
                if (wildcardType.getExtendsBound() != null) {
                    declaredType.accept(this, wildcardType.getExtendsBound());
                } else if (wildcardType.getSuperBound() != null) {
                    declaredType.accept(this, wildcardType.getSuperBound());
                }
            }
            case TypeVariable typeVariable -> {
                final var name = typeVariable.asElement().getSimpleName();
                map.putIfAbsent(name, declaredType);
            }
            default -> {
                //Nothing todo
            }
        }

        return null;
    }

}

class Filer3 implements TypeVisitor<TypeMirror, Map<String, TypeMirror>> {

    private final Types types;

    Filer3(final Types types) {
        this.types = types;
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror, final Map<String, TypeMirror> param) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType, final Map<String, TypeMirror> map) {
        final var componentType = arrayType.getComponentType().accept(this, map);
        return types.getArrayType(componentType);
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType declaredType, final Map<String, TypeMirror> map) {
        final var typeArguments = declaredType.getTypeArguments().stream()
                .map(it -> it.accept(this, map))
                .toArray(TypeMirror[]::new);

        return types.getDeclaredType(
                (TypeElement) declaredType.asElement(),
                typeArguments
        );
    }

    @Override
    public TypeMirror visitMethodType(final ExecutableType methodType, final Map<String, TypeMirror> map) {
        final var typeVariables = new ArrayList<TypeVariable>(methodType.getTypeVariables());

        final var returnType = methodType.getReturnType().accept(this, map);
        final var argumentTypes = methodType.getParameterTypes().stream()
                .map(it -> it.accept(this, map))
                .toList();

        return types.getExecutableType(
                methodType.getMethodSymbol(),
                typeVariables,
                returnType,
                argumentTypes,
                new ArrayList<>()
        );
    }

    @Override
    public TypeMirror visitVariableType(final io.github.potjerodekool.nabu.compiler.type.VariableType variableType, final Map<String, TypeMirror> map) {
        final var interferedType = variableType.getInterferedType().accept(this, map);
        return types.getVariableType(interferedType);
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType, final Map<String, TypeMirror> map) {
        if (wildcardType.getExtendsBound() != null) {
            return wildcardType.getExtendsBound().accept(this, map);
        } else if (wildcardType.getSuperBound() != null) {
            return wildcardType.getSuperBound().accept(this, map);
        } else {
            return wildcardType;
        }
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable, final Map<String, TypeMirror> map) {
        final var name = typeVariable.asElement().getSimpleName();
        return map.getOrDefault(name, typeVariable);
    }
}
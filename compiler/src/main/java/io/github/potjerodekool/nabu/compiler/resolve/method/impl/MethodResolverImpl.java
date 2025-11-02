package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Pair;
import io.github.potjerodekool.nabu.util.Types;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.IntStream;

public class MethodResolverImpl implements MethodResolver {

    private final Types types;

    public MethodResolverImpl(final Types types) {
        this.types = types;
    }

    @Override
    public Optional<ExecutableType> resolveMethod(final MethodInvocationTree methodInvocation) {
        return resolveMethod(methodInvocation, null, null);
    }

    @Override
    public Optional<ExecutableType> resolveMethod(final MethodInvocationTree methodInvocation,
                                                  final Element currentElement,
                                                  final Scope scope) {
        final var methodSelector = methodInvocation.getMethodSelector();
        final var resolvedMethodNameAndSelected = resolveMethodNameAndSelected(methodSelector);

        final String methodName = resolvedMethodNameAndSelected.first();
        final ExpressionTree selected = resolvedMethodNameAndSelected.second();

        final DeclaredType targetType = resolveTargetType(selected, currentElement);
        final boolean onlyStaticCalls = onlyStaticCalls(selected, currentElement);

        final var argumentTypes = methodInvocation.getArguments().stream()
                .map(this::resolveType)
                .toList();

        final var typeArguments = methodInvocation.getTypeArguments().stream()
                .map(this::resolveType)
                .toList();

        return resolveMethod(
                targetType,
                methodName,
                typeArguments,
                argumentTypes,
                onlyStaticCalls,
                scope
        );
    }

    private Pair<String, ExpressionTree> resolveMethodNameAndSelected(final ExpressionTree expression) {
        if (expression instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            final var resolved = resolveMethodNameAndSelected(fieldAccessExpressionTree.getField());
            return new Pair<>(resolved.first(), fieldAccessExpressionTree.getSelected());
        } else {
            final var identifierTree = (IdentifierTree) expression;
            return new Pair<>(identifierTree.getName(), null);
        }
    }

    private DeclaredType resolveTargetType(final ExpressionTree selected,
                                           final Element currentElement) {
        final DeclaredType targetType;

        if (selected == null) {
            if (currentElement instanceof ExecutableElement executableElement) {
                final var clazz = (TypeElement) executableElement.getEnclosingElement();
                targetType = (DeclaredType) clazz.asType();
            } else {
                final var clazz = (TypeElement) currentElement;
                targetType = (DeclaredType) clazz.asType();
            }
        } else {
            final var targetSymbol = selected.getSymbol();

            if (targetSymbol instanceof VariableElement variableElement) {
                targetType = asClassType(variableElement.asType());
            } else if (targetSymbol instanceof TypeElement) {
                targetType = asClassType(targetSymbol.asType());
            } else {
                final var type = resolveType(selected);
                targetType = asClassType(type);
            }
        }

        return targetType;
    }


    private boolean onlyStaticCalls(final ExpressionTree selected,
                                    final Element currentElement) {
        if (currentElement instanceof ExecutableElement executableElement) {
            return executableElement.isStatic();
        } else if (selected == null) {
            return false;
        } else {
            final var targetSymbol = selected.getSymbol();
            return targetSymbol instanceof TypeElement;
        }
    }

    public DeclaredType asClassType(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            return declaredType;
        } else if (typeMirror instanceof VariableType variableType) {
            return asClassType(variableType.getInterferedType());
        } else {
            return types.getErrorType(typeMirror.getClassName());
        }
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

    private Optional<ExecutableType> resolveMethod(final DeclaredType targetType,
                                                   final String methodName,
                                                   final List<TypeMirror> typeArguments,
                                                   final List<TypeMirror> argumentTypes,
                                                   final boolean onlyStaticCalls,
                                                   final Scope scope) {
        if ("super".equals(methodName)) {
            var clazz = (TypeElement) targetType.asElement();
            final var searchType = (DeclaredType) clazz.getSuperclass();
            return doResolveMethod(
                    searchType,
                    "this",
                    typeArguments,
                    argumentTypes,
                    onlyStaticCalls,
                    null);
        } else {
            return doResolveMethod(
                    targetType,
                    methodName,
                    typeArguments,
                    argumentTypes,
                    onlyStaticCalls,
                    scope
            );
        }
    }

    private Optional<ExecutableType> doResolveMethod(final DeclaredType type,
                                                     final String methodName,
                                                     final List<?extends TypeMirror> typeArguments,
                                                     final List<TypeMirror> argumentTypes,
                                                     final boolean onlyStaticCalls,
                                                     final Scope scope) {
        final Optional<ExecutableType> methodTypeOptional;

        final var clazz = (Symbol) type.asElement();
        final List<ExecutableElement> methods;

        if (Constants.THIS.equals(methodName)
                || Constants.INIT.equals(methodName)) {
            methods = ElementFilter.constructorsIn(clazz.getMembers().elements());
        } else {
            methods = ElementFilter.methodsIn(clazz.getEnclosedElements()).stream()
                    .filter(element -> methodFilter(element, methodName, onlyStaticCalls))
                    .toList();
        }

        final var methodTypes = new ArrayList<>(methods.stream()
                .map(method -> (ExecutableType) types.asMemberOf(type, method))
                .map(mt -> transform(mt, typeArguments, argumentTypes))
                .filter(method -> match(method, argumentTypes))
                .toList());

        if (scope != null) {
            resolveMethodInScope(methodName, scope).ifPresent(methodTypes::add);
        }

        if (methodTypes.size() == 1) {
            methodTypeOptional = Optional.of(methodTypes.getFirst());
        } else if (methodTypes.size() > 1) {
            final var bestMatch = bestMatch(methodTypes, argumentTypes);

            if (bestMatch.isPresent()) {
                return bestMatch;
            }

            throw new TodoException("Found multiple candidates for method " + methodName + " in " + clazz.getSimpleName());
        } else {
            final var interfaceMethodOptional = clazz.getInterfaces().stream()
                    .map(interfaceType -> (DeclaredType) interfaceType)
                    .map(interfaceType -> doResolveMethod(
                            interfaceType,
                            methodName,
                            typeArguments,
                            argumentTypes,
                            onlyStaticCalls,
                            null))
                    .findFirst()
                    .orElse(Optional.empty());

            if (interfaceMethodOptional.isPresent()) {
                methodTypeOptional = interfaceMethodOptional;
            } else {
                final var superType = (DeclaredType) clazz.getSuperclass();

                if (superType == null) {
                    methodTypeOptional = Optional.empty();
                } else {
                    methodTypeOptional = doResolveMethod(
                            superType,
                            methodName,
                            typeArguments,
                            argumentTypes,
                            onlyStaticCalls, null);
                }
            }
        }

        return methodTypeOptional;
    }

    private Optional<ExecutableType> bestMatch(final List<ExecutableType> candidates,
                                               final List<TypeMirror> argumentTypes) {
        ExecutableType bestMatch = candidates.getFirst();

        final var matcher = new CandidateMatcher(types);

        candidateLoop:
        for (var candidateIndex = 1; candidateIndex < candidates.size(); candidateIndex++) {
            final var candidate = candidates.get(candidateIndex);

            final var bestMatchParameterTypes = bestMatch.getParameterTypes();
            final var candidateParameterTypes = candidate.getParameterTypes();

            for (int argumentIndex = 0, argumentTypesSize = argumentTypes.size(); argumentIndex < argumentTypesSize; argumentIndex++) {
                final TypeMirror argumentType = argumentTypes.get(argumentIndex);
                matcher.setArgumentType(argumentType);

                if (bestMatchParameterTypes.get(argumentIndex).accept(
                        matcher,
                        candidateParameterTypes.get(argumentIndex)
                )) {
                    bestMatch = candidate;
                    continue candidateLoop;
                }
            }
        }

        return Optional.of(bestMatch);
    }

    @Override
    public ExecutableType transform(final ExecutableType methodType,
                                    final List<? extends TypeMirror> typeArguments,
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
                                 final boolean onlyStaticCalls) {
        if (onlyStaticCalls && !method.isStatic()) {
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

    private Optional<ExecutableType> resolveMethodInScope(final String methodName,
                                                          final Scope scope) {
        final var namedImportScope = scope.getCompilationUnit().getNamedImportScope();
        final var resolvedMethodOptional = resolveMethodInScope(methodName, namedImportScope);

        if (resolvedMethodOptional.isPresent()) {
            return resolvedMethodOptional;
        } else {
            final var startImportScope = scope.getCompilationUnit().getStartImportScope();
            return resolveMethodInScope(methodName, startImportScope);
        }
    }

    private Optional<ExecutableType> resolveMethodInScope(final String methodName,
                                                          final ImportScope importScope) {
        final var symbols = importScope.resolveByName(
                methodName,
                methodFilter()
        );

        final var iterator = symbols.iterator();

        if (iterator.hasNext()) {
            final var first = iterator.next();

            if (iterator.hasNext()) {
                return Optional.empty();
            } else {
                return Optional.of((ExecutableType) first.asType());
            }
        }

        return Optional.empty();
    }

    private Predicate<Element> methodFilter() {
        return symbol -> symbol instanceof MethodSymbol;
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
    public TypeMirror visitVariableType(final VariableType variableType, final Map<String, TypeMirror> map) {
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

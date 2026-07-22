package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.lang.model.element.*;
import io.github.potjerodekool.nabu.compiler.type.impl.CArrayType;
import io.github.potjerodekool.nabu.compiler.type.impl.CUnknownType;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Elements;
import io.github.potjerodekool.nabu.util.Pair;
import io.github.potjerodekool.nabu.util.Types;
import lombok.extern.java.Log;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class CompleteMethodResolver implements MethodResolver {

    private final Elements elements;
    private final Types types;
    private final OverrideChecker overrideChecker;
    private final Logger logger = Logger.getLogger(CompleteMethodResolver.class.getName());

    public CompleteMethodResolver(final Elements elements, final Types types) {
        this.elements = elements;
        this.types = types;
        this.overrideChecker = new OverrideChecker(types);
    }

    @Override
    public Optional<ExecutableType> resolveMethod(final MethodInvocationTree methodInvocation,
                                                  final Element currentElement,
                                                  final Scope scope) {
        final var methodSelector = methodInvocation.getMethodSelector();
        final var resolved = resolveMethodNameAndSelected(methodSelector);

        final String methodName = resolved.first();
        final ExpressionTree selected = resolved.second();

        final DeclaredType targetType = resolveTargetType(selected, currentElement);
        final boolean onlyStaticCalls = onlyStaticCalls(selected, currentElement);

        final var typeArguments = methodInvocation.getTypeArguments().stream()
                .map(this::resolveType)
                .toList();

        return resolveMethod(
                targetType,
                methodName,
                typeArguments,
                methodInvocation.getArguments(),
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

    private DeclaredType resolveTargetType(final ExpressionTree selected, final Element currentElement) {
        if (selected == null) {
            if (currentElement instanceof ExecutableElement executableElement) {
                final var clazz = (TypeElement) executableElement.getEnclosingElement();
                return (DeclaredType) clazz.asType();
            } else {
                final var clazz = (TypeElement) currentElement;
                return (DeclaredType) clazz.asType();
            }
        } else {
            final var targetSymbol = selected.getSymbol();

            if (targetSymbol instanceof VariableElement variableElement) {
                return asClassType(variableElement.asType());
            } else if (targetSymbol instanceof TypeElement) {
                return asClassType(targetSymbol.asType());
            } else {
                final var type = resolveType(selected);
                return asClassType(type);
            }
        }
    }

    private boolean onlyStaticCalls(final ExpressionTree selected, final Element currentElement) {
        if (currentElement instanceof ExecutableElement executableElement) {
            return executableElement.isStatic();
        } else if (selected == null) {
            return false;
        } else {
            final var targetSymbol = selected.getSymbol();
            return targetSymbol instanceof TypeElement;
        }
    }

    private DeclaredType asClassType(final TypeMirror typeMirror) {
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
            final var methodType = methodInvocationTree.getMethodType();
            if (methodType != null) {
                return methodType.getReturnType();
            } else {
                return new CUnknownType();
            }
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
                                                   final List<ExpressionTree> arguments,
                                                   final boolean onlyStaticCalls,
                                                   final Scope scope) {
        if ("super".equals(methodName)) {
            var clazz = (TypeElement) targetType.asElement();
            final var searchType = (DeclaredType) clazz.getSuperclass();
            return doResolveMethod(
                    searchType,
                    "this",
                    typeArguments,
                    arguments,
                    onlyStaticCalls,
                    null);
        } else {
            return doResolveMethod(
                    targetType,
                    methodName,
                    typeArguments,
                    arguments,
                    onlyStaticCalls,
                    scope
            );
        }
    }

    private Optional<ExecutableType> doResolveMethod(final DeclaredType type,
                                                     final String methodName,
                                                     final List<? extends TypeMirror> typeArguments,
                                                     final List<ExpressionTree> arguments,
                                                     final boolean onlyStaticCalls,
                                                     final Scope scope) {
        final Optional<ExecutableType> methodTypeOptional;

        final var clazz = type.asElement();
        final List<ExecutableElement> methods;

        if (Constants.THIS.equals(methodName)
                || Constants.INIT.equals(methodName)) {
            methods = ElementFilter.constructorsIn(clazz.getEnclosedElements());
        } else {
            methods = ElementFilter.methodsIn(clazz.getEnclosedElements()).stream()
                    .filter(element -> methodFilter(element, methodName, onlyStaticCalls))
                    .toList();
        }

        final var methodTypes = new ArrayList<>(methods.stream()
                .map(method -> transform(
                        type,
                        method,
                        typeArguments,
                        arguments
                ))
                .filter(methodAndArgTypes -> match(methodAndArgTypes.first(), methodAndArgTypes.second()))
                .map(Pair::first)
                .toList());

        if (scope != null) {
            resolveMethodInScope(methodName, scope).ifPresent(methodTypes::add);
        }

        if (methodTypes.size() == 1) {
            methodTypeOptional = Optional.of(methodTypes.getFirst());
        } else if (methodTypes.size() > 1) {
            final var argumentTypes = arguments.stream()
                    .map(this::resolveType)
                    .toList();

            final var bestMatch = bestMatch(methodTypes, argumentTypes);
            if (bestMatch.isPresent()) {
                return bestMatch;
            }

            methodTypeOptional = Optional.empty();
        } else {
            final var classSymbol = (ClassSymbol) clazz;

            final var interfaceMethodOptional = classSymbol.getInterfaces().stream()
                    .map(interfaceType -> mapToType(type, interfaceType))
                    .map(interfaceType -> (DeclaredType) interfaceType)
                    .map(interfaceType -> doResolveMethod(
                            interfaceType,
                            methodName,
                            typeArguments,
                            arguments,
                            onlyStaticCalls,
                            null))
                    .findFirst()
                    .orElse(Optional.empty());

            if (interfaceMethodOptional.isPresent()) {
                methodTypeOptional = interfaceMethodOptional;
            } else {
                final var superType = (DeclaredType) classSymbol.getSuperclass();
                if (superType == null) {
                    methodTypeOptional = Optional.empty();
                } else {
                    methodTypeOptional = doResolveMethod(
                            superType,
                            methodName,
                            typeArguments,
                            arguments,
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
                    break;
                }
            }
        }

        return Optional.of(bestMatch);
    }

    public Pair<ExecutableType, List<TypeMirror>> transform(final DeclaredType targetType,
                                                            final ExecutableElement method,
                                                            final List<? extends TypeMirror> typeArguments,
                                                            final List<ExpressionTree> arguments) {
        final var methodType = (ExecutableType) method.asType();
        final var typeMapFiller = new TypeMapFiller(types);
        final var typeMap = typeMapFiller.getTypeMap();
        final var typeMapApplier = new TypeApplier(typeMap, types);

        if (!method.isStatic()) {
            targetType.accept(typeMapFiller, null);
        }

        if (typeArguments.isEmpty()) {
            final var targetTypeArguments = targetType.getTypeArguments();
            if (!targetTypeArguments.isEmpty()) {
                final var declaredType = (DeclaredType) targetType.asTypeElement().asType();
                final var typeArgs = declaredType.getTypeArguments();

                for (var i = 0; i < targetTypeArguments.size(); i++) {
                    typeArgs.get(i).accept(typeMapFiller, targetTypeArguments.get(i));
                }
            }
        } else {
            final var typeVariables = methodType.getTypeVariables();
            for (var typeVariableIndex = 0; typeVariableIndex < typeVariables.size(); typeVariableIndex++) {
                if (typeVariableIndex < typeArguments.size()) {
                    final var typeArgument = typeArguments.get(typeVariableIndex);
                    typeVariables.get(typeVariableIndex).accept(typeMapFiller, typeArgument);
                }
            }
            applyTypes(typeArguments, methodType.getTypeVariables(), typeMapApplier);
        }

        final var argumentTypes = arguments.stream()
                .map(this::resolveType)
                .toList();

        final var argTypes = applyTypes(argumentTypes, typeMapApplier);
        fillTypeMap(methodType.getParameterTypes(), argTypes, typeMapFiller);

        final var parameterTypes = applyTypes(methodType.getParameterTypes(), typeMapApplier);
        final var returnType = methodType.getReturnType().accept(typeMapApplier, null);
        final var thrownTypes = methodType.getThrownTypes().stream()
                .map(thrownType -> thrownType.accept(typeMapApplier, null))
                .toList();

        final var transformedMethodType = types.getExecutableType(
                method,
                methodType.getTypeVariables(),
                returnType,
                parameterTypes,
                thrownTypes
        );

        return new Pair<>(transformedMethodType, argTypes);
    }

    private void fillTypeMap(final List<? extends TypeMirror> parameterTypes,
                             final List<TypeMirror> argTypes,
                             final TypeMapFiller typeMapFiller) {
        for (var i = 0; i < parameterTypes.size(); i++) {
            if (i < argTypes.size()) {
                final var paramType = parameterTypes.get(i);
                final var argType = argTypes.get(i);
                paramType.accept(typeMapFiller, argType);
            }
        }
    }

    private List<TypeMirror> applyTypes(final List<? extends TypeMirror> types,
                                        final TypeVisitor<TypeMirror, TypeMirror> typeVisitor) {
        return types.stream()
                .map(parameterType -> parameterType.accept(typeVisitor, null))
                .collect(Collectors.toList());
    }

    private void applyTypes(final List<? extends TypeMirror> firstTypes,
                            final List<? extends TypeMirror> secondTypes,
                            final TypeVisitor<TypeMirror, TypeMirror> typeVisitor) {
        for (var i = 0; i < firstTypes.size(); i++) {
            final var firstType = getOrNull(firstTypes, i);
            final var secondType = getOrNull(secondTypes, i);

            if (firstType != null && secondType != null) {
                firstType.accept(typeVisitor, secondType);
            }
        }
    }

    private TypeMirror getOrNull(final List<? extends TypeMirror> types, final int index) {
        if (index < types.size()) {
            return types.get(index);
        }
        return null;
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
        final var parameterTypes = methodType.getParameterTypes();

        if (argumentTypes.isEmpty() && parameterTypes.isEmpty()) {
            return true;
        }

        var matchCount = 0;
        final var parameterCount = parameterTypes.size();
        final var lastParameterIndex = parameterCount - 1;
        final var argumentCount = argumentTypes.size();
        var isVarArg = false;
        var index = 0;

        for (; index < argumentCount; index++) {
            final var argumentType = argumentTypes.get(index);
            TypeMirror parameterType;

            if (isVarArg) {
                parameterType = parameterTypes.getLast();
            } else if (index <= lastParameterIndex) {
                parameterType = parameterTypes.get(index);
            } else {
                parameterType = null;
            }

            if (parameterType != null) {
                if (isVarArgType(parameterType)) {
                    final var arrayType = (ArrayType) parameterType;
                    parameterType = arrayType.getComponentType();
                    isVarArg = true;
                }

                if (types.isAssignable(argumentType, parameterType)) {
                    matchCount++;
                }
            }
        }

        if (lastParameterIndex > -1
                && isVarArgType(parameterTypes.getLast())
                && matchCount >= parameterCount - 1) {
            return true;
        }

        return argumentCount == parameterCount && matchCount == parameterCount;
    }

    private boolean isVarArgType(final TypeMirror typeMirror) {
        return typeMirror instanceof CArrayType arrayType && arrayType.isVarArgs();
    }

    private Optional<ExecutableType> resolveMethodInScope(final String methodName, final Scope scope) {
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
        final var symbols = importScope.resolveByName(methodName, methodFilter());

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
        return symbol -> symbol instanceof io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
    }

    private TypeMirror mapToType(final TypeMirror sourceType, final TypeMirror targetType) {
        final var typeMapFiller = new TypeMapFiller(types);
        sourceType.accept(typeMapFiller, null);
        final var typeMap = typeMapFiller.getTypeMap();

        final var applier = new TypeMapApplier(typeMap, types);
        return targetType.accept(applier, null);
    }

    @Override
    public Optional<ExecutableType> resolveMethod(final MethodInvocationTree methodInvocationTree,
                                                  final Scope scope) {
        final var selector = methodInvocationTree.getMethodSelector();

        if (selector instanceof IdentifierTree identifierTree) {
            var searchType = (DeclaredType) scope.getCurrentClass().asType();
            final var methodName = identifierTree.getName();

            final boolean isConstructorCall;

            if (Constants.THIS.equals(methodName)) {
                isConstructorCall = true;
            } else if (Constants.SUPER.equals(methodName)) {
                isConstructorCall = true;
                searchType = (DeclaredType) searchType.asTypeElement().getSuperclass();
            } else {
                isConstructorCall = false;
            }

            do {
                var executableType = resolveMethod(
                        methodInvocationTree,
                        searchType,
                        scope,
                        isConstructorCall
                );

                if (executableType != null) {
                    return Optional.of(executableType);
                } else {
                    searchType = (DeclaredType) searchType.getEnclosingType();
                }
            } while (searchType != null);

            return fallback(methodInvocationTree, scope);
        } else if (selector instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            var searchType = getTypeOf(fieldAccessExpressionTree.getSelected());
            final var methodName = fieldAccessExpressionTree.getField().getName();

            final boolean isConstructorCall;

            if (Constants.SUPER.equals(methodName)) {
                searchType = (DeclaredType) searchType.asTypeElement().getSuperclass();
                isConstructorCall = true;
            } else {
                isConstructorCall = false;
            }

            final var resolvedMethod = resolveMethod(
                    methodInvocationTree,
                    searchType,
                    scope,
                    isConstructorCall
            );

            if (resolvedMethod != null) {
                return Optional.of(resolvedMethod);
            }
        }

        return fallback(methodInvocationTree, scope);
    }

    private DeclaredType getTypeOf(final ExpressionTree expressionTree) {
        if (expressionTree instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            return getTypeOf(fieldAccessExpressionTree.getField());
        }

        if (expressionTree.getSymbol() != null) {
            final var type = expressionTree.getSymbol().asType();
            return type instanceof VariableType variableType
                    ? (DeclaredType) variableType.getInterferedType()
                    : (DeclaredType) type;
        } else {
            final var type = expressionTree.getType();
            return type instanceof VariableType variableType
                    ? (DeclaredType) variableType.getInterferedType()
                    : (DeclaredType) type;
        }
    }

    private Optional<ExecutableType> fallback(final MethodInvocationTree methodInvocationTree,
                                              final Scope scope) {
        return resolveMethod(methodInvocationTree, (Element) null, scope);
    }

    private String resolveMethodName(final MethodInvocationTree methodInvocationTree) {
        final var selector = methodInvocationTree.getMethodSelector();

        if (selector instanceof IdentifierTree methodName) {
            return methodName.getName();
        } else {
            final var fieldAccess = (FieldAccessExpressionTree) selector;
            return fieldAccess.getField().getName();
        }
    }

    private ExecutableType resolveMethod(final MethodInvocationTree methodInvocationTree,
                                         final DeclaredType searchType,
                                         final Scope scope,
                                         final boolean isConstructorCall) {
        final var methodName = resolveMethodName(methodInvocationTree);
        final var arguments = methodInvocationTree.getArguments();

        final var candidates = getPotentiallyApplicableMethods(
                methodInvocationTree,
                searchType,
                methodName,
                arguments,
                scope,
                isConstructorCall
        );

        final var phase1Results = phase1StrictInvocation(candidates, arguments);
        if (!phase1Results.isEmpty()) {
            return chooseMostSpecificMethod(phase1Results, searchType).method();
        }

        final var phase2Results = phase2LooseInvocation(candidates, arguments);
        if (!phase2Results.isEmpty()) {
            return chooseMostSpecificMethod(phase2Results, searchType).method();
        }

        final var phase3Results = phase3VariableArity(candidates, arguments);
        if (!phase3Results.isEmpty()) {
            return chooseMostSpecificMethod(phase3Results, searchType).method();
        }

        throw new MethodResolveException(
                "No applicable method found for: " + methodName
        );
    }

    public List<ExecutableType> getPotentiallyApplicableMethods(final MethodInvocationTree methodInvocation,
                                                                final DeclaredType searchType,
                                                                final String methodName,
                                                                final List<ExpressionTree> arguments,
                                                                final Scope scope,
                                                                final boolean isConstructorCall) {
        final var typeArguments = methodInvocation.getTypeArguments().stream()
                .map(this::resolveType)
                .toList();

        final var currentClass = scope != null
                ? scope.getCurrentClass()
                : searchType.asTypeElement();

        final var methodCollection = new ArrayList<ExecutableType>();
        collectMethods(searchType, methodCollection, isConstructorCall);

        return methodCollection.stream()
                .filter(method -> isPotentiallyApplicable(
                        methodName,
                        arguments,
                        method,
                        currentClass,
                        isConstructorCall
                ))
                .toList();
    }

    void collectMethods(final DeclaredType declaredType,
                        final List<ExecutableType> methodCollection,
                        final boolean isConstructorCall) {
        final var typeElement = declaredType.asTypeElement();
        final var methods = isConstructorCall
                ? ElementFilter.constructorsIn(typeElement.getEnclosedElements())
                : ElementFilter.methodsIn(typeElement.getEnclosedElements()).stream()
                .toList();

        final var map = SimpleTypeMapFiller.fill(declaredType);
        final var mapper = new SimpleTypeMapApplier(map, types);
        final var methodTypes = methods.stream()
                .map(Element::asType)
                .map(methodType -> (ExecutableType) methodType.accept(mapper, null))
                .toList();

        for (final var methodType : methodTypes) {
            final var symbol = methodType.getMethodSymbol();
            final var alreadyPresent = methodCollection.stream()
                    .anyMatch(m -> m.getMethodSymbol() == symbol);
            if (!alreadyPresent) {
                methodCollection.add(methodType);
            }
        }

        final var superClazz = typeElement.getSuperclass();
        if (superClazz != null) {
            final var mappedType = mapType((DeclaredType) superClazz, map);
            collectMethods(mappedType, methodCollection, isConstructorCall);
        }

        typeElement.getInterfaces().stream()
                .map(it -> (DeclaredType) it)
                .forEach(iface -> {
                    final var mappedType = mapType(iface, map);
                    collectMethods(mappedType, methodCollection, isConstructorCall);
                });
    }

    private DeclaredType mapType(final DeclaredType declaredType, final Map<String, TypeMirror> map) {
        return (DeclaredType) SimpleTypeMapApplier.apply(map, declaredType, types);
    }

    public boolean isPotentiallyApplicable(final String methodName,
                                           final List<ExpressionTree> arguments,
                                           final ExecutableType method,
                                           final TypeElement caller,
                                           final boolean isConstructorCall) {
        final var methodSymbol = method.getMethodSymbol();

        if (!isConstructorCall && !methodName.equals(methodSymbol.getSimpleName())) {
            return false;
        }

        if (!AccessChecker.isAccessible(methodSymbol, caller)) {
            return false;
        }

        final var parameterTypes = method.getParameterTypes();

        if (methodSymbol.isVarArgs()) {
            final var fixedParamCount = parameterTypes.size() - 1;

            if (arguments.size() < fixedParamCount) {
                return false;
            }

            for (int index = 0; index < fixedParamCount; index++) {
                final var argument = arguments.get(index);
                final var argumentType = resolveType(argument);

                if (!isPotentiallyCompatible(argumentType, parameterTypes.get(index))) {
                    return false;
                }
            }

            final var varargType = ((ArrayType) parameterTypes.get(fixedParamCount)).getComponentType();
            for (int i = fixedParamCount; i < arguments.size(); i++) {
                final var argument = arguments.get(i);
                final var argumentType = resolveType(argument);

                if (!isPotentiallyCompatible(argumentType, varargType) &&
                        !isPotentiallyCompatible(argumentType, parameterTypes.get(fixedParamCount))) {
                    return false;
                }
            }

            return true;
        } else {
            if (parameterTypes.size() != arguments.size()) {
                return false;
            }

            for (int i = 0; i < arguments.size(); i++) {
                final var argument = arguments.get(i);
                final var argumentType = resolveType(argument);

                if (!isPotentiallyCompatible(argumentType, parameterTypes.get(i))) {
                    return false;
                }
            }

            return true;
        }
    }

    private boolean isPotentiallyCompatible(final TypeMirror sourceType, final TypeMirror targetType) {
        if (sourceType == null) {
            return !targetType.isPrimitiveType();
        }

        if (sourceType == targetType) {
            return true;
        }

        if (!sourceType.isPrimitiveType() && !targetType.isPrimitiveType()) {
            return true;
        }

        if (sourceType.isPrimitiveType() && targetType.isPrimitiveType()) {
            return true;
        }

        // JLS 15.12.2.1: if one is primitive and the other is a reference type,
        // boxing/unboxing could make it compatible.
        return true;
    }

    private List<ApplicableMethod> phase1StrictInvocation(
            final List<ExecutableType> candidates,
            final List<ExpressionTree> arguments) {
        final var argumentTypes = arguments.stream()
                .map(this::resolveType)
                .toList();

        return candidates.stream()
                .filter(method -> !method.getMethodSymbol().isVarArgs())
                .filter(method -> {
                    final var parameterTypes = method.getParameterTypes();
                    return parameterTypes.size() == argumentTypes.size();
                })
                .filter(method -> {
                    final var parameterTypes = method.getParameterTypes();

                    for (int i = 0; i < argumentTypes.size(); i++) {
                        final var argument = arguments.get(i);
                        if (isImplicitlyTypedLambda(argument)) {
                            continue;
                        }

                        if (!isStrictlyCompatible(argumentTypes.get(i), parameterTypes.get(i))) {
                            return false;
                        }
                    }

                    return true;
                })
                .map(method -> {
                    final var parameterTypes = method.getParameterTypes();
                    double specificity = calculateSpecificity(parameterTypes, argumentTypes);
                    return new ApplicableMethod(method, 1, specificity);
                })
                .toList();
    }

    private boolean isImplicitlyTypedLambda(final ExpressionTree expressionTree) {
        return expressionTree instanceof LambdaExpressionTree lambdaExpressionTree
                && lambdaExpressionTree.getParameterKind() == LambdaExpressionTree.ParameterKind.IMPLICIT;
    }

    private boolean isStrictlyCompatible(final TypeMirror sourceType, final TypeMirror targetType) {
        if (sourceType == null) {
            return !targetType.isPrimitiveType();
        }

        if (types.isSameType(sourceType, targetType)) {
            return true;
        }

        if (isWideningPrimitive(sourceType, targetType)) {
            return true;
        }

        if (!sourceType.isPrimitiveType() && !targetType.isPrimitiveType()) {
            return types.isAssignable(sourceType, targetType);
        }

        return false;
    }

    private double calculateSpecificity(final List<? extends TypeMirror> parameterTypes,
                                        final List<? extends TypeMirror> argumentTypes) {
        double specificity = 0.0;

        for (int i = 0; i < Math.min(parameterTypes.size(), argumentTypes.size()); i++) {
            final var paramType = parameterTypes.get(i);
            final var argType = argumentTypes.get(i);

            if (argType == null) {
                continue;
            }

            if (argType.isPrimitiveType() && !paramType.isPrimitiveType()) {
                if (isBoxingCompatible(argType, paramType)) {
                    specificity += 1.0;
                }
            }

            if (!paramType.isPrimitiveType() && !argType.isPrimitiveType()) {
                specificity += getClassDepth((DeclaredType) paramType);
            }
        }

        return specificity;
    }

    private int getClassDepth(final DeclaredType clazz) {
        final var objectType = types.getObjectType();

        int depth = 0;
        DeclaredType current = clazz;
        while (current != null && current != objectType) {
            depth++;
            current = (DeclaredType) current.asTypeElement().getSuperclass();
        }
        return depth;
    }

    boolean isWideningPrimitive(final TypeMirror source, final TypeMirror target) {
        if (!source.isPrimitiveType() || !target.isPrimitiveType()) {
            return false;
        }

        return switch (source.getKind()) {
            case BYTE -> switch (target.getKind()) {
                case SHORT, INT, LONG, FLOAT, DOUBLE -> true;
                default -> false;
            };
            case SHORT -> switch (target.getKind()) {
                case INT, LONG, FLOAT, DOUBLE -> true;
                default -> false;
            };
            case CHAR -> switch (target.getKind()) {
                case INT, LONG, FLOAT, DOUBLE -> true;
                default -> false;
            };
            case INT -> switch (target.getKind()) {
                case LONG, FLOAT, DOUBLE -> true;
                default -> false;
            };
            case LONG -> switch (target.getKind()) {
                case FLOAT, DOUBLE -> true;
                default -> false;
            };
            case FLOAT -> target.getKind() == TypeKind.DOUBLE;
            default -> false;
        };
    }

    boolean isBoxingCompatible(final TypeMirror source, final TypeMirror target) {
        if (!source.isPrimitiveType()) {
            return false;
        }

        final var boxedClass = types.boxedClass((PrimitiveType) source);
        final var boxedType = boxedClass.asType();

        // JLS 5.1.7 boxing + JLS 5.1.5 widening reference conversion:
        // the boxed type must be assignable to the target type.
        return types.isAssignable(boxedType, target);
    }

    private ApplicableMethod chooseMostSpecificMethod(
            final List<ApplicableMethod> applicableMethods,
            final DeclaredType searchType) {
        if (applicableMethods.isEmpty()) {
            throw new MethodResolveException("No applicable methods found");
        }

        if (applicableMethods.size() == 1) {
            return applicableMethods.getFirst();
        }

        int minPhase = applicableMethods.stream()
                .mapToInt(ApplicableMethod::phase)
                .min()
                .orElse(Integer.MAX_VALUE);

        List<ApplicableMethod> samePhase = applicableMethods.stream()
                .filter(m -> m.phase() == minPhase)
                .toList();

        samePhase = new ArrayList<>(samePhase);
        samePhase.sort(Comparator.comparingDouble(ApplicableMethod::specificity));

        if (samePhase.size() > 1) {
            final var filtered = filterOverridden(samePhase);

            if (filtered.size() == 1) {
                return filtered.getFirst();
            }

            ApplicableMethod first = samePhase.get(0);
            ApplicableMethod second = samePhase.get(1);

            if (first.method().getMethodSymbol().getKind() == ElementKind.CONSTRUCTOR) {
                if (isMemberOf(first.method(), searchType)) {
                    return first;
                }
            }

            if (second.method().getMethodSymbol().getKind() == ElementKind.CONSTRUCTOR) {
                if (isMemberOf(second.method(), searchType)) {
                    return second;
                }
            }

            if (first.specificity() == second.specificity()) {
                throw new MethodResolveException(
                        "Ambiguous method invocation: " + first.method() + " vs " + second.method());
            }
        }

        return samePhase.getFirst();
    }

    private List<ApplicableMethod> filterOverridden(final List<ApplicableMethod> methods) {
        if (methods.size() < 2) {
            return methods;
        }

        final var overwritten = new HashSet<ApplicableMethod>();

        for (final var method : methods) {
            for (final var other : methods) {
                if (method == other) continue;

                final var left = method.method().getMethodSymbol();
                final var right = other.method().getMethodSymbol();
                final var leftType = (TypeElement) left.getEnclosingElement();

                if (overrideChecker.overrides(left, right, leftType)) {
                    overwritten.add(other);
                }
            }
        }

        final var result = new ArrayList<ApplicableMethod>();
        for (final var method : methods) {
            if (!overwritten.contains(method)) {
                result.add(method);
            }
        }

        return result;
    }

    private boolean isMemberOf(final ExecutableType method, final DeclaredType searchType) {
        final var declaringClass = (TypeElement) method.getMethodSymbol().getEnclosingElement();
        final var searchClass = searchType.asTypeElement();
        return declaringClass.getQualifiedName().equals(searchClass.getQualifiedName());
    }

    private List<ApplicableMethod> phase2LooseInvocation(
            final List<ExecutableType> candidates,
            final List<ExpressionTree> arguments) {
        final var argumentTypes = arguments.stream()
                .map(this::resolveType)
                .toList();

        final var applicableMethods = new ArrayList<ApplicableMethod>();

        for (var method : candidates) {
            if (method.getMethodSymbol().isVarArgs()) {
                continue;
            }

            final var parameterTypes = method.getParameterTypes();

            if (parameterTypes.size() != argumentTypes.size()) {
                continue;
            }

            boolean isApplicable = true;
            for (int i = 0; i < argumentTypes.size(); i++) {
                if (!isLooselyCompatible(argumentTypes.get(i), parameterTypes.get(i))) {
                    isApplicable = false;
                    break;
                }
            }

            if (isApplicable) {
                double specificity = calculateSpecificity(parameterTypes, argumentTypes);
                applicableMethods.add(new ApplicableMethod(method, 2, specificity));
            }
        }

        return applicableMethods;
    }

    private boolean isLooselyCompatible(final TypeMirror sourceType, final TypeMirror targetType) {
        if (sourceType == null) {
            return !targetType.isPrimitiveType();
        }

        if (isStrictlyCompatible(sourceType, targetType)) {
            return true;
        }

        if (isUnboxingCompatible(sourceType, targetType)) {
            return true;
        }

        if (isBoxingCompatible(sourceType, targetType)) {
            return true;
        }

        return false;
    }

    boolean isUnboxingCompatible(final TypeMirror source, final TypeMirror target) {
        if (!target.isPrimitiveType() || !types.isBoxType(source)) {
            return false;
        }

        final var sourceTypeName = source.asTypeElement().getQualifiedName();

        return switch (sourceTypeName) {
            case Constants.BOOLEAN -> target.getKind() == TypeKind.BOOLEAN;
            case Constants.BYTE -> target.getKind() == TypeKind.BYTE;
            case Constants.SHORT -> target.getKind() == TypeKind.SHORT;
            case Constants.CHARACTER -> target.getKind() == TypeKind.CHAR;
            case Constants.INTEGER -> target.getKind() == TypeKind.INT;
            case Constants.LONG -> target.getKind() == TypeKind.LONG;
            case Constants.FLOAT -> target.getKind() == TypeKind.FLOAT;
            case Constants.DOUBLE -> target.getKind() == TypeKind.DOUBLE;
            default -> false;
        };
    }

    private List<ApplicableMethod> phase3VariableArity(
            final List<ExecutableType> candidates,
            final List<ExpressionTree> arguments) {
        final var argumentTypes = arguments.stream()
                .map(this::resolveType)
                .toList();

        final var applicableMethods = new ArrayList<ApplicableMethod>();

        for (var method : candidates) {
            final var parameterTypes = method.getParameterTypes();

            if (method.getMethodSymbol().isVarArgs()) {
                int fixedParamCount = parameterTypes.size() - 1;

                if (argumentTypes.size() < fixedParamCount) {
                    continue;
                }

                boolean isApplicable = true;
                for (int i = 0; i < fixedParamCount; i++) {
                    if (!isLooselyCompatible(argumentTypes.get(i), parameterTypes.get(i))) {
                        isApplicable = false;
                        break;
                    }
                }

                if (!isApplicable) {
                    continue;
                }

                final var varargType = ((ArrayType) parameterTypes.get(fixedParamCount)).getComponentType();

                for (int i = fixedParamCount; i < argumentTypes.size(); i++) {
                    if (!isLooselyCompatible(argumentTypes.get(i), varargType)) {
                        isApplicable = false;
                        break;
                    }
                }

                if (isApplicable) {
                    double specificity = calculateSpecificity(parameterTypes, argumentTypes);
                    applicableMethods.add(new ApplicableMethod(method, 3, specificity));
                }
            } else {
                if (parameterTypes.size() != argumentTypes.size()) {
                    continue;
                }

                boolean isApplicable = true;
                for (int i = 0; i < argumentTypes.size(); i++) {
                    if (!isLooselyCompatible(argumentTypes.get(i), parameterTypes.get(i))) {
                        isApplicable = false;
                        break;
                    }
                }

                if (isApplicable) {
                    double specificity = calculateSpecificity(parameterTypes, argumentTypes);
                    applicableMethods.add(new ApplicableMethod(method, 3, specificity));
                }
            }
        }

        return applicableMethods;
    }
}

package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.lang.model.element.*;
import io.github.potjerodekool.nabu.compiler.type.impl.CArrayType;
import io.github.potjerodekool.nabu.compiler.type.impl.CUnknownType;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Elements;
import io.github.potjerodekool.nabu.util.Pair;
import io.github.potjerodekool.nabu.util.Types;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class MethodResolverImpl implements MethodResolver {

    private final Logger logger = Logger.getLogger(MethodResolverImpl.class.getName());
    private final Elements elements;
    private final Types types;

    public MethodResolverImpl(final Elements elements,
                              final Types types) {
        this.elements = elements;
        this.types = types;
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

            final var bestMatch = bestMatch(
                    methodTypes,
                    argumentTypes);

            if (bestMatch.isPresent()) {
                return bestMatch;
            }

            throw new TodoException("Found multiple candidates for method " + methodName + " in " + clazz.getSimpleName());
        } else {
            final var interfaceMethodOptional = clazz.getInterfaces().stream()
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
                final var superType = (DeclaredType) clazz.getSuperclass();

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

        fillTypeMap(
                methodType.getParameterTypes(),
                argTypes,
                typeMapFiller
        );

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

        return new Pair<>(
                transformedMethodType,
                argTypes
        );
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
                .map(parameterType -> parameterType.accept(
                        typeVisitor,
                        null
                ))
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

    private TypeMirror getOrNull(final List<? extends TypeMirror> types,
                                 final int index) {
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
            //Last parameter type is vararg but no argument was provided.
            return true;
        }

        //No vararg so all must match.
        return argumentCount == parameterCount
                && matchCount == parameterCount;
    }

    private boolean isVarArgType(TypeMirror typeMirror) {
        return typeMirror instanceof CArrayType arrayType
                && arrayType.isVarArgs();
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

    private TypeMirror mapToType(final TypeMirror sourceType,
                                 final TypeMirror targetType) {
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

            logger.log(LogLevel.INFO, "1 Resolving " + methodName);

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

            //TODO resolve via static import.

            return fallback(methodInvocationTree, scope);
        } else if (selector instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            var searchType = getTypeOf(fieldAccessExpressionTree.getSelected());
            final var methodName = fieldAccessExpressionTree.getField().getName();
            logger.log(LogLevel.INFO, "2 Resolving " + methodName);
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
        return resolveMethod(
                methodInvocationTree,
                (Element) null,
                scope
        );
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

        // Step 1: Identify Potentially Applicable Methods
        final var potentiallyApplicable = getPotentiallyApplicableMethods(
                methodInvocationTree,
                searchType,
                methodName,
                arguments,
                scope,
                isConstructorCall
        );

        // Step 2: Phase 1 - Strict Invocation (no boxing/unboxing, no varargs)

        final var phase1Results = phase1StrictInvocation(
                potentiallyApplicable,
                arguments
        );
        final var applicableMethods = new ArrayList<>(phase1Results);

        // If methods found in phase 1, choose the most specific and return
        if (!applicableMethods.isEmpty()) {
            return chooseMostSpecificMethod(applicableMethods, searchType).method();
        }

        // Step 3: Phase 2 - Loose Invocation (with boxing/unboxing, no varargs)
        List<ApplicableMethod> phase2Results =
                phase2LooseInvocation(potentiallyApplicable, arguments);
        applicableMethods.addAll(phase2Results);

        // If methods found in phase 2, choose the most specific and return
        if (!applicableMethods.isEmpty()) {
            return chooseMostSpecificMethod(applicableMethods, searchType).method();
        }

        // Step 4: Phase 3 - Variable Arity Invocation (with boxing/unboxing/varargs)
        List<ApplicableMethod> phase3Results =
                phase3VariableArity(potentiallyApplicable, arguments);
        applicableMethods.addAll(phase3Results);

        // If methods found in phase 3, choose the most specific and return
        if (!applicableMethods.isEmpty()) {
            return chooseMostSpecificMethod(applicableMethods, searchType).method();
        }

        throw new MethodResolveException(
                "No applicable method found for: " + methodName +
                        " with argument types: ");

    }

    //632
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

        final var allMembers = elements.getAllMembers(searchType.asTypeElement());

        final var methods = isConstructorCall
                ? ElementFilter.constructorsIn(allMembers)
                : ElementFilter.methodsIn(allMembers);

        final var methodCollection = new ArrayList<ExecutableType>();
        collectMethods(searchType, methodCollection, isConstructorCall);

        /*
        methods.stream()
         .map(method -> {

                    final var transformedResult = transform(
                            searchType,
                            method,
                            typeArguments,
                            arguments
                    );

                    return transformedResult.first();
                })
         */

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

    private void collectMethods(final DeclaredType declaredType,
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

        methodCollection.addAll(methodTypes);

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

    private DeclaredType mapType(final DeclaredType declaredType,
                                 final Map<String, TypeMirror> map) {
        return (DeclaredType) SimpleTypeMapApplier.apply(
                map,
                declaredType,
                types
        );
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

            // Check varargs
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

    private boolean isPotentiallyCompatible(final TypeMirror sourceType,
                                            final TypeMirror targetType) {
        if (sourceType == null) {
            // null is potentially compatible with any reference type
            return !targetType.isPrimitiveType();
        }

        // Same type
        if (sourceType == targetType) {
            return true;
        }

        // Reference types
        if (!sourceType.isPrimitiveType() && !targetType.isPrimitiveType()) {
            return true; // Could be compatible after type checking
        }

        // Primitive types
        if (sourceType.isPrimitiveType() && targetType.isPrimitiveType()) {
            return true; // Could be compatible via widening
        }

        return false;
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
        return expressionTree instanceof LambdaExpressionTree lambdaExpressionTree && lambdaExpressionTree.getParameterKind() == LambdaExpressionTree.ParameterKind.IMPLICIT;
    }

    private boolean isStrictlyCompatible(final TypeMirror sourceType,
                                         final TypeMirror targetType) {
        if (sourceType == null) {
            // null is compatible with any reference type
            return !targetType.isPrimitiveType();
        }

        // Same type
        if (types.isSameType(sourceType, targetType)) {
            return true;
        }

        // Widening primitive conversion
        if (isWideningPrimitive(sourceType, targetType)) {
            return true;
        }

        // Widening reference conversion (subclass to superclass)
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

            // Penalize boxing conversions (less specific)
            if (argType.isPrimitiveType() && !paramType.isPrimitiveType()) {
                if (isBoxingCompatible(argType, paramType)) {
                    specificity += 1.0;
                }
            }

            // Penalize type hierarchy depth
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


    private boolean isWideningPrimitive(final TypeMirror source,
                                        final TypeMirror target) {
        if (!source.isPrimitiveType() || !target.isPrimitiveType()) {
            return false;
        }

        // Define widening conversions
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

    private boolean isBoxingCompatible(final TypeMirror source,
                                       final TypeMirror target) {
        if (!source.isPrimitiveType() || !types.isBoxType(target)) {
            return false;
        }

        final var boxTypeName = target.asTypeElement().getQualifiedName();

        return switch (source.getKind()) {
            case BOOLEAN -> Constants.BOOLEAN.equals(boxTypeName);
            case BYTE -> Constants.BYTE.equals(boxTypeName);
            case SHORT -> Constants.SHORT.equals(boxTypeName);
            case CHAR -> Constants.CHARACTER.equals(boxTypeName);
            case INT -> Constants.INTEGER.equals(boxTypeName);
            case LONG -> Constants.LONG.equals(boxTypeName);
            case FLOAT -> Constants.FLOAT.equals(boxTypeName);
            case DOUBLE -> Constants.DOUBLE.equals(boxTypeName);
            default -> false;
        };
    }

    private ApplicableMethod chooseMostSpecificMethod(
            final List<ApplicableMethod> applicableMethods, final DeclaredType searchType) {

        if (applicableMethods.isEmpty()) {
            throw new MethodResolveException("No applicable methods found");
        }

        if (applicableMethods.size() == 1) {
            return applicableMethods.getFirst();
        }

        // First, find the minimum phase
        int minPhase = applicableMethods.stream()
                .mapToInt(ApplicableMethod::phase)
                .min()
                .orElse(Integer.MAX_VALUE);

        // Filter to only methods in the minimum phase
        List<ApplicableMethod> samePhase = applicableMethods.stream()
                .filter(m -> m.phase() == minPhase)
                .toList();

        // Sort by specificity (lower is better)
        samePhase = new ArrayList<>(samePhase);
        samePhase.sort(Comparator.comparingDouble(ApplicableMethod::specificity));

        // Check for ambiguity
        if (samePhase.size() > 1) {
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
                final var methods = filterOverwritten(samePhase);

                if (methods.size() == 1) {
                    return methods.getFirst();
                }

                throw new MethodResolveException(
                        "Ambiguous method invocation: " + first.method() + " vs " + second.method());
            }
        }

        return samePhase.getFirst();
    }

    private List<ApplicableMethod> filterOverwritten(final List<ApplicableMethod> methods) {
        if (methods.size() < 2) {
            return methods;
        }

        var remaining = methods.stream()
                .filter(method -> !overwrites(method, methods))
                .toList();

        final List<ApplicableMethod> checkList = new ArrayList<>(methods);

        final var overwritten = new HashSet<ApplicableMethod>();

        for (ApplicableMethod applicableMethod : checkList) {
            for (final var applicableMethod2 : methods) {
                if (applicableMethod != applicableMethod2) {
                    if (elements.overrides(
                            applicableMethod.method().getMethodSymbol(),
                            applicableMethod2.method().getMethodSymbol(),
                            (TypeElement) applicableMethod.method().getMethodSymbol().getEnclosingElement()
                    )) {
                        overwritten.add(applicableMethod);
                    }
                }
            }
        }

        final var result = new ArrayList<ApplicableMethod>();

        loop:
        for (final var method : methods) {
            for (final var applicableMethod : overwritten) {
                if (method == applicableMethod) {
                    continue loop;
                }
            }
            result.add(method);
        }

        return result;
    }

    public boolean overwrites(final ApplicableMethod applicableMethod,
                              final List<ApplicableMethod> methods) {
        final var overriddenMethod = applicableMethod.method().getMethodSymbol();
        return methods.stream()
                .anyMatch(otherMethod ->
                {
                    final var overrider = otherMethod.method().getMethodSymbol();
                    final var first = overrider != overriddenMethod;
                    final var second = elements.overrides(overrider, overriddenMethod, (TypeElement) otherMethod.method().getMethodSymbol().getEnclosingElement());

                    return first && second;
                });
    }

    private boolean isMemberOf(final ExecutableType method,
                               final DeclaredType searchType) {
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

        List<ApplicableMethod> applicableMethods = new ArrayList<>();

        for (var method : candidates) {
            // Check if variable arity (varargs)
            if (method.getMethodSymbol().isVarArgs()) {
                continue; // Skip varargs methods in phase 2
            }

            final var parameterTypes = method.getParameterTypes();

            // Check arity
            if (parameterTypes.size() != argumentTypes.size()) {
                continue; // Wrong number of arguments
            }

            // Check loose type compatibility (with boxing/unboxing)
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

    private boolean isLooselyCompatible(final TypeMirror sourceType,
                                        final TypeMirror targetType) {
        if (sourceType == null) {
            // null is compatible with any reference type
            return !targetType.isPrimitiveType();
        }

        // First check strict compatibility
        if (isStrictlyCompatible(sourceType, targetType)) {
            return true;
        }

        // Unboxing conversion: wrapper type -> primitive type
        if (isUnboxingCompatible(sourceType, targetType)) {
            return true;
        }

        // Boxing conversion: primitive type -> wrapper type
        if (isBoxingCompatible(sourceType, targetType)) {
            return true;
        }

        return false;
    }

    private boolean isUnboxingCompatible(final TypeMirror source,
                                         final TypeMirror target) {
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

        List<ApplicableMethod> applicableMethods = new ArrayList<>();

        for (var method : candidates) {
            final var parameterTypes = method.getParameterTypes();

            if (method.getMethodSymbol().isVarArgs()) {
                // Variable arity method
                int fixedParamCount = parameterTypes.size() - 1;

                // Check if argument count is at least the fixed parameter count
                if (argumentTypes.size() < fixedParamCount) {
                    continue;
                }

                // Check fixed parameters
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

                // Check varargs parameter
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
                // Fixed arity method - also check in phase 3 with loose compatibility
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

class TypeMapApplier implements TypeVisitor<TypeMirror, TypeMirror> {

    private final TypeMap typeMap;
    private final Types types;

    public TypeMapApplier(final TypeMap typeMap,
                          final Types types) {
        this.typeMap = typeMap;
        this.types = types;
    }

    @Override
    public TypeMirror visitPrimitiveType(final PrimitiveType primitiveType,
                                         final TypeMirror param) {
        return primitiveType;
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType,
                                     final TypeMirror param) {
        return arrayType;
    }

    @Override
    public TypeMirror visitVariableType(final VariableType variableType,
                                        final TypeMirror param) {
        return variableType.getInterferedType().accept(this, param);
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror,
                                       final TypeMirror param) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitNoType(final NoType noType,
                                  final TypeMirror param) {
        return noType;
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType declaredType,
                                        final TypeMirror param) {
        final var typeArguments = declaredType.getTypeArguments().stream()
                .map(typeArgument ->
                        typeArgument.accept(this, param))
                .toArray(TypeMirror[]::new);

        return types.getDeclaredType(
                declaredType.asTypeElement(),
                typeArguments
        );
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType,
                                        final TypeMirror param) {
        return switch (wildcardType.getBoundKind()) {
            case UNBOUND -> types.getWildcardType(null, null);
            case EXTENDS -> {
                final var extendsBound = wildcardType.getExtendsBound().accept(this, param);
                yield types.getWildcardType(extendsBound, null);
            }
            case SUPER -> {
                final var superBound = wildcardType.getSuperBound().accept(this, param);
                yield types.getWildcardType(null, superBound);
            }
        };
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable,
                                        final TypeMirror param) {
        final var name = typeVariable.asElement().getSimpleName();
        return this.typeMap.getOrDefault(name, typeVariable);
    }
}

class TypeMap {

    private final Map<String, TypeMirror> map = new HashMap<>();

    public Map<String, TypeMirror> getMap() {
        return map;
    }

    public void put(final String name,
                    final TypeMirror typeArg) {
        Objects.requireNonNull(typeArg);
        this.map.putIfAbsent(name, typeArg);
    }

    public TypeMirror get(final String name) {
        return this.map.get(name);
    }

    public TypeMirror getOrDefault(final String name,
                                   final TypeMirror defaultType) {
        return this.map.getOrDefault(name, defaultType);
    }
}

class TypeApplier implements TypeVisitor<TypeMirror, TypeMirror> {

    private final TypeMap typeMap;
    private final Types types;

    TypeApplier(final TypeMap typeMap,
                final Types types) {
        this.typeMap = typeMap;
        this.types = types;
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror,
                                       final TypeMirror param) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitVariableType(final VariableType variableType,
                                        final TypeMirror param) {
        if (variableType.getInterferedType() != null) {
            return variableType.getInterferedType();
        } else {
            return variableType;
        }
    }

    @Override
    public TypeMirror visitPrimitiveType(final PrimitiveType primitiveType,
                                         final TypeMirror param) {
        return primitiveType;
    }

    @Override
    public TypeMirror visitNoType(final NoType noType,
                                  final TypeMirror param) {
        return noType;
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType,
                                     final TypeMirror param) {
        final var componentType = arrayType.getComponentType().accept(this, null);
        var newArrayType = types.getArrayType(componentType);

        if (arrayType.isVarArgs()) {
            newArrayType = newArrayType.makeVarArg();
        }

        return newArrayType;
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType declaredType,
                                        final TypeMirror otherType) {
        if (declaredType.getTypeArguments().isEmpty()) {
            return declaredType;
        }

        final TypeMirror[] typeArguments;

        if (otherType instanceof DeclaredType otherDeclaredType) {
            final var typeArgs = declaredType.getTypeArguments();
            final var otherTypeArgs = otherDeclaredType.getTypeArguments();
            typeArguments = new TypeMirror[typeArgs.size()];

            for (var i = 0; i < typeArgs.size(); i++) {
                final var typeArg = typeArgs.get(i);
                final var otherTypeArg = otherTypeArgs.get(i);
                typeArguments[i] = typeArg.accept(this, otherTypeArg);
            }
        } else {
            typeArguments = declaredType.getTypeArguments().stream()
                    .map(typeArgument -> typeArgument.accept(this, null))
                    .toArray(TypeMirror[]::new);
        }

        return types.getDeclaredType(
                declaredType.asTypeElement(),
                typeArguments
        );
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable,
                                        final TypeMirror other) {
        if (other != null) {
            return other;
        } else {
            final var name = typeVariable.asElement().getSimpleName();
            var resolvedType = typeMap.get(name);

            if (resolvedType instanceof DeclaredType) {
                resolvedType = resolvedType.accept(this, null);
            } else if (resolvedType == null) {
                resolvedType = typeVariable;
            }

            return resolvedType;
        }
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType,
                                        final TypeMirror param) {
        return switch (wildcardType.getBoundKind()) {
            case UNBOUND -> types.getWildcardType(null, null);
            case EXTENDS -> {
                var extendsBound = wildcardType.getExtendsBound().accept(this, null);

                if (extendsBound.isPrimitiveType()) {
                    extendsBound = types.boxedClass((PrimitiveType) extendsBound).asType();
                }

                yield types.getWildcardType(extendsBound, null);
            }
            case SUPER -> {
                final var isTypeVar = wildcardType.getSuperBound() instanceof TypeVariable;

                var superBound = wildcardType.getSuperBound().accept(this, null);
                if (superBound.isPrimitiveType()) {
                    superBound = types.boxedClass((PrimitiveType) superBound).asType();
                }
                yield types.getWildcardType(null, superBound);
            }
        };
    }

    @Override
    public TypeMirror visitMethodType(final ExecutableType methodType, final TypeMirror param) {
        return TypeVisitor.super.visitMethodType(methodType, param);
    }
}

// 640 (5.12)
class ApplicablePhase1 {

    public boolean isApplicable(final MethodInvocationTree methodInvocationTree,
                                final ExecutableType method) {
        method.getMethodSymbol();
        method.getTypeArguments();

        return true;
    }

    private boolean isGeneric(final MethodSymbol method) {
        return !method.getTypeParameters().isEmpty()
                || isGeneric(method.getReturnType())
                || method.getParameters().stream()
                .anyMatch(param -> isGeneric(param.asType()));
    }

    private boolean isGeneric(final TypeMirror typeMirror) {
        return typeMirror.getKind() == TypeKind.TYPEVAR
                || typeMirror.getKind() == TypeKind.WILDCARD;
    }

    public boolean isApplicable(final ExpressionTree expressionTree) {
        if (expressionTree instanceof LambdaExpressionTree lambdaExpressionTree
                && lambdaExpressionTree.getParameterKind() == LambdaExpressionTree.ParameterKind.IMPLICIT) {
            return true;
        }
        //TODO check method reference

        return true;
    }
}


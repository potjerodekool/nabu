package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.type.impl.CArrayType;
import io.github.potjerodekool.nabu.compiler.type.impl.CUnknownType;
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
import java.util.stream.Collectors;

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
                                                     final List<? extends TypeMirror> typeArguments,
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
                .map(method -> transform(
                        type,
                        method,
                        typeArguments,
                        argumentTypes
                ))
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
                    .map(interfaceType -> mapToType(type, interfaceType))
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

    private ExecutableType transform(final DeclaredType targetType,
                                     final ExecutableElement method,
                                     final List<? extends TypeMirror> typeArguments,
                                     final List<TypeMirror> argumentTypes) {
        final var methodType = (ExecutableType) method.asType();

        final var typeMapFiller = new TypeMapFiller();
        final var typeMap = typeMapFiller.getTypeMap();
        final var typeMapApplier = new TypeMapApplier(typeMap, types);
        final var typeApplier2 = new TypeMapApplier2(typeMap, types);

        if (!method.isStatic()) {
            targetType.accept(typeMapFiller, null);
        }

        if (!typeArguments.isEmpty()) {
            applyTypes(typeArguments, methodType.getTypeVariables(), typeApplier2);
        }

        var parameterTypes = applyTypes(methodType.getParameterTypes(), typeMapApplier);
        var argTypes = applyTypes(argumentTypes, typeMapApplier);

        argTypes = applyTypes(argTypes, parameterTypes, typeApplier2);
        parameterTypes = applyTypes(parameterTypes, argTypes, typeApplier2);

        final var returnType = methodType.getReturnType().accept(typeMapApplier, null);
        final var thrownTypes = methodType.getThrownTypes().stream()
                .map(thrownType -> thrownType.accept(typeMapApplier, null))
                .toList();

        return types.getExecutableType(
                method,
                methodType.getTypeVariables(),
                returnType,
                parameterTypes,
                thrownTypes
        );
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

    private List<TypeMirror> applyTypes(final List<? extends TypeMirror> firstTypes,
                                        final List<? extends TypeMirror> secondTypes,
                                        final TypeVisitor<TypeMirror, TypeMirror> typeVisitor) {
        final var types = new ArrayList<TypeMirror>();

        for (var i = 0; i < firstTypes.size(); i++) {
            final var firstType = getOrNull(firstTypes, i);
            final var secondType = getOrNull(secondTypes, i);

            if (firstType != null && secondType != null) {
                types.add(firstType.accept(typeVisitor, secondType));
            } else if (firstType != null) {
                types.add(firstType);
            }
        }

        return types;
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

    public boolean match(final ExecutableType methodType,
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
        final var typeMapFiller = new TypeMapFiller();
        sourceType.accept(typeMapFiller, null);
        final var typeMap = typeMapFiller.getTypeMap();

        final var applier = new TypeMapApplier(typeMap, types);
        return targetType.accept(applier, null);
    }
}

class TypeMapFiller implements TypeVisitor<Object, Object> {

    private final TypeMap typeMap = new TypeMap();

    public TypeMap getTypeMap() {
        return typeMap;
    }

    @Override
    public Object visitUnknownType(final TypeMirror typeMirror, final Object param) {
        return typeMirror;
    }

    @Override
    public Object visitDeclaredType(final DeclaredType declaredType, final Object param) {
        final var typeArguments = declaredType.getTypeArguments();
        final var typeParameters = declaredType.asTypeElement()
                .getTypeParameters();
        final var typeParameterCount = typeParameters.size();

        for (int index = 0; index < typeParameterCount; index++) {
            final var typeParameter = typeParameters.get(index);
            final var name = typeParameter.getSimpleName();
            final var typeArg = typeArguments.get(index);
            this.typeMap.put(name, typeArg);
        }

        return null;
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
    public TypeMirror visitPrimitiveType(final PrimitiveType primitiveType, final TypeMirror param) {
        return primitiveType;
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType, final TypeMirror param) {
        return arrayType;
    }

    @Override
    public TypeMirror visitVariableType(final VariableType variableType, final TypeMirror param) {
        return variableType.getInterferedType().accept(this, param);
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror,
                                       final TypeMirror param) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitNoType(final NoType noType, final TypeMirror param) {
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

class TypeMapApplier2 implements TypeVisitor<TypeMirror, TypeMirror> {

    private final TypeMap typeMap;
    private final Types types;

    public TypeMapApplier2(final TypeMap typeMap,
                           final Types types) {
        this.typeMap = typeMap;
        this.types = types;
    }

    @Override
    public TypeMirror visitPrimitiveType(final PrimitiveType primitiveType, final TypeMirror param) {
        return primitiveType;
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType, final TypeMirror param) {
        return arrayType;
    }

    @Override
    public TypeMirror visitVariableType(final VariableType variableType, final TypeMirror param) {
        return variableType.getInterferedType().accept(this, param);
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror, final TypeMirror param) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType argumentType,
                                        final TypeMirror parameterType) {
        if (parameterType instanceof DeclaredType) {
            final var argumentTypeArgs = argumentType.getTypeArguments();
            final var parameterTypeArgs = parameterType.getTypeArguments();

            if (argumentTypeArgs.size() != parameterTypeArgs.size()) {
                return argumentType;
            }

            final var newTypeArgs = new TypeMirror[argumentTypeArgs.size()];

            for (var i = 0; i < argumentTypeArgs.size(); i++) {
                newTypeArgs[i] = argumentTypeArgs.get(i).accept(this, parameterTypeArgs.get(i));
            }

            return types.getDeclaredType(
                    argumentType.asTypeElement(),
                    newTypeArgs
            );
        } else {
            if (parameterType instanceof TypeVariable typeVariable) {
                typeMap.put(typeVariable.asElement().getSimpleName(), argumentType);
            }
            return parameterType;
        }
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable argumentType,
                                        final TypeMirror parameterType) {
        return this.typeMap.getOrDefault(argumentType.asElement().getSimpleName(), argumentType);
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType argumentType,
                                        final TypeMirror parameterType) {
        if (parameterType instanceof TypeVariable typeVariable) {
            this.typeMap.put(typeVariable.asElement().getSimpleName(), argumentType);
            return argumentType;
        } else {
            return parameterType;
        }
    }
}

class TypeMap {

    private final Map<String, TypeMirror> map = new HashMap<>();

    public Map<String, TypeMirror> getMap() {
        return map;
    }

    public void put(final String name,
                    final TypeMirror typeArg) {
        this.map.putIfAbsent(name, typeArg);
    }

    public TypeMirror getOrDefault(final String name,
                                   final TypeMirror defaultType) {
        return this.map.getOrDefault(name, defaultType);
    }
}
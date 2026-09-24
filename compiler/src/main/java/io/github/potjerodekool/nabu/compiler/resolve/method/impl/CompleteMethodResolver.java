package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.compiler.type.impl.CArrayType;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;
import io.github.potjerodekool.nabu.compiler.type.impl.CUnknownType;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.compiler.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.resolve.impl.TypeEnter;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.TreeUtils;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Elements;
import io.github.potjerodekool.nabu.util.Pair;
import io.github.potjerodekool.nabu.util.TypePrinter;
import io.github.potjerodekool.nabu.util.Types;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class CompleteMethodResolver implements MethodResolver {

    private final Elements elements;
    private final Types types;
    private final TreeUtils treeUtils;
    private final OverrideChecker overrideChecker;
    private final TypeEnter typeEnter;
    private final Logger logger = Logger.getLogger(CompleteMethodResolver.class.getName());

    private Scope currentScope;

    public CompleteMethodResolver(final Elements elements,
                                  final Types types,
                                  final TreeUtils treeUtils,
                                  final TypeEnter typeEnter) {
        this.elements = elements;
        this.types = types;
        this.treeUtils = treeUtils;
        this.typeEnter = typeEnter;
        this.overrideChecker = new OverrideChecker(types);
    }

    @Override
    public Optional<ExecutableType> resolveMethod(final MethodInvocationTree methodInvocation,
                                                  final Element currentElement,
                                                  final Scope scope) {
        if (methodInvocation.getMethodType() != null) {
            return Optional.of(methodInvocation.getMethodType());
        }
        currentScope = scope;
        final var methodSelector = methodInvocation.getMethodSelector();
        final var resolved = resolveMethodNameAndSelected(methodSelector);

        final String methodName = resolved.first();
        final ExpressionTree selected = resolved.second();

        final DeclaredType targetType = resolveTargetType(selected, currentElement);
        final boolean onlyStaticCalls = onlyStaticCalls(selected, currentElement);

        final var typeArguments = methodInvocation.getTypeArguments().stream()
                .map(this::resolveType)
                .toList();

        final var resolvedMethod = resolveMethod(
                targetType,
                methodName,
                typeArguments,
                methodInvocation.getArguments(),
                onlyStaticCalls,
                scope
        );

        resolvedMethod.ifPresent(methodInvocation::setMethodType);

        return resolvedMethod;
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
        if (selected instanceof IdentifierTree identifierTree
                && (Constants.SUPER.equals(identifierTree.getName())
                || Constants.THIS.equals(identifierTree.getName()))
                && identifierTree.getSymbol() == null) {
            return resolveTargetType(null, currentElement);
        }

        if (selected == null) {
            if (currentElement instanceof ExecutableElement executableElement) {
                final var clazz = (TypeElement) executableElement.getEnclosingElement();
                return (DeclaredType) clazz.asType();
            } else if (currentElement instanceof TypeElement typeElement) {
                return (DeclaredType) typeElement.asType();
            } else {
                // currentElement is geen ExecutableElement of TypeElement
                // (bijv. een lambda- of synthetisch element): zoek de
                // dichtstbijzijnde enclosing TypeElement.
                var enclosingElement = currentElement != null
                        ? currentElement.getEnclosingElement()
                        : null;

                while (enclosingElement != null && !(enclosingElement instanceof TypeElement)) {
                    enclosingElement = enclosingElement.getEnclosingElement();
                }

                if (enclosingElement instanceof TypeElement enclosingType) {
                    return asClassType(enclosingType.asType());
                }

                return types.getErrorType("");
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
            } else if (currentScope != null) {
                final var resolved = resolveMethod(methodInvocationTree, currentScope);
                final var resolvedType = methodInvocationTree.getMethodType();
                if (resolvedType != null) {
                    return resolvedType.getReturnType();
                }
                return resolved.map(ExecutableType::getReturnType).orElse(new CUnknownType());
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
            return new CUnknownType();
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
            final var searchType = clazz.getSuperclass() instanceof DeclaredType superDeclaredType
                    ? superDeclaredType
                    : null;

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

        if (type == null) {
            return Optional.empty();
        }

        final var clazz = type.asElement();

        List<ExecutableElement> methods;

        if (Constants.THIS.equals(methodName)
                || Constants.INIT.equals(methodName)) {
            methods = ElementFilter.constructorsIn(clazz.getEnclosedElements());
        } else {
            methods = ElementFilter.methodsIn(clazz.getEnclosedElements()).stream()
                    .filter(element -> methodFilter(element, methodName, onlyStaticCalls))
                    .toList();
        }

        if (clazz.getKind() == ElementKind.ENUM
                && !Constants.THIS.equals(methodName)
                && !Constants.INIT.equals(methodName)
                && !containsMethod(methods, methodName)) {
            final var implicitMethod = resolveImplicitEnumMethod((TypeElement) clazz, methodName, arguments);
            if (implicitMethod.isPresent()) {
                methods = new ArrayList<>(methods);
                methods.add(implicitMethod.get());
            }
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

    private boolean containsMethod(final List<ExecutableElement> methods,
                                   final String methodName) {
        return methods.stream()
                .anyMatch(method -> method.getSimpleName().equals(methodName));
    }

    /**
     * Resolveert de impliciete enum-methoden {@code valueOf(String)},
     * {@code values()} en {@code $values()}. Deze methoden worden pas in
     * de lowering-fase (EnumCodeGenerator) aan de ClassSymbol toegevoegd,
     * waardoor ze tijdens de resolutiefase ontbreken. Hier bouwen we een
     * tijdelijke methode op die de handtekening dekt, zodat aanroepen als
     * {@code Style.valueOf(...)} gewoon opgelost kunnen worden.
     */
    private Optional<ExecutableElement> resolveImplicitEnumMethod(final TypeElement clazz,
                                                                  final String methodName,
                                                                  final List<ExpressionTree> arguments) {
        if ("$values".equals(methodName)) {
            if (!arguments.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(createImplicitEnumMethod(
                    clazz,
                    methodName,
                    types.getArrayType(clazz.asType()),
                    Flags.PUBLIC,
                    List.of()
            ));
        } else if ("values".equals(methodName)) {
            if (!arguments.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(createImplicitEnumMethod(
                    clazz,
                    methodName,
                    types.getArrayType(clazz.asType()),
                    Flags.PUBLIC,
                    List.of()
            ));
        } else if ("valueOf".equals(methodName)
                && arguments.size() == 1) {
            final var stringType = stringType(clazz);
            if (stringType == null) {
                return Optional.empty();
            }
            final var parameter = new VariableSymbol(
                    ElementKind.PARAMETER,
                    Flags.PUBLIC,
                    "arg0",
                    stringType,
                    (Symbol) clazz,
                    null
            );
            return Optional.of(createImplicitEnumMethod(
                    clazz,
                    methodName,
                    clazz.asType(),
                    Flags.PUBLIC,
                    List.of(parameter)
            ));
        }
        return Optional.empty();
    }

    private ExecutableElement createImplicitEnumMethod(final TypeElement clazz,
                                                       final String methodName,
                                                       final TypeMirror returnType,
                                                       final long flags,
                                                       final List<VariableElement> parameters) {
        return new MethodSymbol(
                ElementKind.METHOD,
                flags + Flags.STATIC + Flags.SYNTHETIC,
                methodName,
                (Symbol) clazz,
                null,
                Collections.emptyList(),
                returnType,
                Collections.emptyList(),
                parameters,
                Collections.emptyList()
        );
    }

    private TypeMirror stringType(final Element clazz) {
        return typeEnter.getStringType();
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
        final var returnType = methodType.getReturnType() != null
                ? methodType.getReturnType().accept(typeMapApplier, null)
                : null;
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

        // Javac-semantiek: een losse String[]-argument dat exact op de
        // gehele varargs-parameter past (varargs-'array-form'), bv.
        // `execute(String... args)` aangeroepen met `execute(args)`.
        if (argumentCount == parameterCount
                && lastParameterIndex >= 0
                && isVarArgType(parameterTypes.getLast())
                && types.isAssignable(argumentTypes.getLast(), parameterTypes.getLast())) {
            return true;
        }

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
        return symbol -> symbol instanceof MethodSymbol;
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
        currentScope = scope;
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

            if (searchType == null) {
                logger.log(LogLevel.WARN, "Searchtype is NULL in IdentifierTree branch, methodName " + methodName);
                return Optional.empty();
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
                    final var enclosingType = searchType.getEnclosingType();

                    if (enclosingType instanceof DeclaredType declaredEnclosing) {
                        searchType = declaredEnclosing;
                    } else {
                        break;
                    }
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

            if (searchType == null) {
                if ("clone".equals(methodName)) {
                    final var selectedNode = fieldAccessExpressionTree.getSelected();
                    final TypeMirror selectedReturn;
                    if (selectedNode instanceof MethodInvocationTree selectedInvocation
                            && selectedInvocation.getMethodType() != null) {
                        selectedReturn = selectedInvocation.getMethodType().getReturnType();
                    } else {
                        selectedReturn = selectedNode != null && selectedNode.getType() != null
                                ? selectedNode.getType()
                                : (selectedNode != null ? treeUtils.typeOf(selectedNode) : null);
                    }
                    if (selectedReturn instanceof ArrayType arrayReturn) {
                        final var objectTypeElement = elements.getTypeElement(scope.findModuleElement(), Constants.OBJECT);
                        if (objectTypeElement != null) {
                            final var cloneSymbol = objectTypeElement.getEnclosedElements().stream()
                                    .filter(element -> element instanceof ExecutableElement ee
                                            && "clone".equals(ee.getSimpleName()))
                                    .map(element -> (ExecutableElement) element)
                                    .findFirst()
                                    .orElse(null);
                            if (cloneSymbol != null) {
                                return Optional.of(new CMethodType(
                                        cloneSymbol,
                                        arrayReturn,
                                        List.of(),
                                        arrayReturn,
                                        List.of(),
                                        List.of()
                                ));
                            }
                        }
                    }
                }
                logger.log(LogLevel.WARN,  String.format("Searchtype is NULL in FieldAccessExpressionTree branch, methodName %s, %s", methodName, fieldAccessExpressionTree));
                return Optional.empty();
            }

            if (searchType instanceof ArrayType arrayType) {
                if ("clone".equals(methodName)) {
                    final var objectTypeElement = elements.getTypeElement(scope.findModuleElement(), Constants.OBJECT);
                    if (objectTypeElement == null) {
                        return Optional.empty();
                    }
                    final var cloneSymbol = objectTypeElement.getEnclosedElements().stream()
                            .filter(element -> element instanceof ExecutableElement ee
                                    && "clone".equals(ee.getSimpleName()))
                            .map(element -> (ExecutableElement) element)
                            .findFirst()
                            .orElse(null);
                    if (cloneSymbol != null) {
                        return Optional.of(new CMethodType(
                                cloneSymbol,
                                arrayType,
                                List.of(),
                                arrayType,
                                List.of(),
                                List.of()
                        ));
                    }
                }
                return Optional.empty();
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
        TypeMirror type = null;

        if (expressionTree instanceof MethodInvocationTree methodInvocationTree) {
            if (methodInvocationTree.getMethodType() != null) {
                type = methodInvocationTree.getMethodType().getReturnType();
            } else if (currentScope != null) {
                final var resolvedMethod = resolveMethod(methodInvocationTree, currentScope);
                type = resolvedMethod.map(ExecutableType::getReturnType).orElse(null);
            }
            if (type instanceof TypeVariable) {
                final var unmasked = unmaskFluentType(methodInvocationTree);
                if (unmasked != null) {
                    type = unmasked;
                }
            }
        } else if (expressionTree instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            final var fieldType = fieldAccessExpressionTree.getField().getType();
            if (fieldType instanceof DeclaredType) {
                return (DeclaredType) fieldType;
            }
            final var selectedType = getTypeOf(fieldAccessExpressionTree.getSelected());
            if (selectedType != null) {
                return selectedType;
            }
            type = fieldAccessExpressionTree.getType();
        } else if (expressionTree instanceof NewClassExpression newClassExpression) {
            // new X(...).method(): het type van de nieuw-gealloceerde
            // instantie komt uit de type-tracking van de new-expressie,
            // of uit het gealloceerde class-symbool zelf.
            type = newClassExpression.getType();
            if (!(type instanceof DeclaredType)) {
                type = getTypeOf(newClassExpression.getName());
            }
        } else if (expressionTree instanceof ArrayAccessExpressionTree arrayAccessExpressionTree) {
            type = arrayAccessExpressionTree.getExpression().getType();
            if (type instanceof ArrayType arrayType) {
                type = arrayType.getComponentType();
            }
        }

        if (type == null) {
            type = expressionTree.getType();
        }

        if (type == null && expressionTree.getSymbol() != null) {
            type = expressionTree.getSymbol().asType();
        }

        if (type instanceof VariableType variableType) {
            final var interferedType = variableType.getInterferedType();
            if (interferedType instanceof DeclaredType declaredType) {
                return declaredType;
            }
            return null;
        }

        if (type instanceof TypeVariable typeVariable) {
            final var upperType = typeVariable.getUpperBound();
            if (upperType instanceof DeclaredType upperDeclared) {
                return upperDeclared;
            }
            if (upperType instanceof VariableType interferedVar) {
                final var bound = interferedVar.getInterferedType();
                if (bound instanceof DeclaredType boundDeclared) {
                    return boundDeclared;
                }
            }
            return null;
        }

        return type instanceof DeclaredType declaredType
                ? declaredType
                : null;
    }

    private DeclaredType unmaskFluentType(final MethodInvocationTree methodInvocationTree) {
        if (!(methodInvocationTree.getMethodSelector() instanceof FieldAccessExpressionTree fieldAccess)) {
            return null;
        }
        final var receiverType = getTypeOf(fieldAccess.getSelected());
        if (receiverType != null && receiverType.asTypeElement() != null) {
            return receiverType;
        }
        return null;
    }

    private Optional<ExecutableType> fallback(final MethodInvocationTree methodInvocationTree,
                                              final Scope scope) {
        return resolveMethod(methodInvocationTree, null, scope);
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
            return inferMethodTypeParameters(
                    selectApplicableMethod(phase1Results, searchType).method(),
                    arguments
            );
        }

        final var phase2Results = phase2LooseInvocation(candidates, arguments);
        if (!phase2Results.isEmpty()) {
            return inferMethodTypeParameters(
                    selectApplicableMethod(phase2Results, searchType).method(),
                    arguments
            );
        }

        final var phase3Results = phase3VariableArity(candidates, arguments);
        if (!phase3Results.isEmpty()) {
            return inferMethodTypeParameters(
                    selectApplicableMethod(phase3Results, searchType).method(),
                    arguments
            );
        }

        return null;
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
        final var typeElement = resolveMemberSourceElement(declaredType.asTypeElement());

        if (typeElement instanceof ClassSymbol classSymbol) {
            classSymbol.complete();
        }

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

    private TypeElement resolveMemberSourceElement(final TypeElement typeElement) {
        if (typeEnter == null || typeElement == null
                || typeElement.getQualifiedName() == null) {
            return typeElement;
        }

        if (typeEnter.isSourceEntered(typeElement.getQualifiedName().toString())) {
            final var sourceSymbol = typeEnter.findSourceSymbol(typeElement.getQualifiedName().toString());
            return sourceSymbol != null ? sourceSymbol : typeElement;
        }

        return typeElement;
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
        if (!(expressionTree instanceof LambdaExpressionTree lambdaExpressionTree)) {
            return false;
        }
        if (lambdaExpressionTree.getParameterKind() == LambdaExpressionTree.ParameterKind.IMPLICIT) {
            return true;
        }
        return lambdaExpressionTree.getVariables().stream()
                .allMatch(v -> v.getType() == null || v.getType().isError());
    }

    private ExecutableType inferMethodTypeParameters(final ExecutableType method,
                                                     final List<ExpressionTree> arguments) {
        final var methodTypeVars = method.getTypeVariables();
        if (methodTypeVars.isEmpty()) {
            return method;
        }

        final var paramTypes = method.getParameterTypes();
        var argTypes = arguments.stream()
                .map(this::resolveType)
                .toList();

        final var argInferenceMap = new java.util.HashMap<String, TypeMirror>();
        for (int i = 0; i < Math.min(paramTypes.size(), argTypes.size()); i++) {
            collectArgumentInferences(paramTypes.get(i), argTypes.get(i), argInferenceMap);
        }

        if (!argInferenceMap.isEmpty()) {
            argTypes = argTypes.stream()
                    .map(at -> substituteTypeVariables(at, argInferenceMap))
                    .toList();
        }

        final var methodInferenceMap = new java.util.HashMap<String, TypeMirror>();
        for (int i = 0; i < Math.min(paramTypes.size(), argTypes.size()); i++) {
            collectInferences(paramTypes.get(i), argTypes.get(i), methodInferenceMap, methodTypeVars);
        }

        final var allInferenceMap = new java.util.HashMap<String, TypeMirror>();
        allInferenceMap.putAll(argInferenceMap);
        allInferenceMap.putAll(methodInferenceMap);

        if (allInferenceMap.isEmpty()) {
            return method;
        }

        final var returnType = substituteTypeVariables(method.getReturnType(), allInferenceMap);
        final var newParamTypes = paramTypes.stream()
                .map(pt -> substituteTypeVariables(pt, allInferenceMap))
                .toList();
        final var newThrownTypes = method.getThrownTypes().stream()
                .map(tt -> substituteTypeVariables(tt, allInferenceMap))
                .toList();

        return types.getExecutableType(
                method.getMethodSymbol(),
                method.getTypeVariables(),
                returnType,
                newParamTypes,
                newThrownTypes
        );
    }

    private void collectArgumentInferences(final TypeMirror paramType,
                                           final TypeMirror argType,
                                           final java.util.HashMap<String, TypeMirror> inferenceMap) {
        if (paramType instanceof WildcardType wt && argType instanceof TypeVariable argTv) {
            if (wt.getBound() != null) {
                inferenceMap.putIfAbsent(argTv.asElement().getSimpleName(), wt.getBound());
            }
        } else if (paramType instanceof WildcardType wt && argType instanceof DeclaredType argDeclared) {
            final var paramBound = wt.getBound();
            if (paramBound instanceof DeclaredType paramBoundDeclared) {
                if (paramBoundDeclared.asTypeElement().getQualifiedName()
                        .equals(argDeclared.asTypeElement().getQualifiedName())) {
                    final var paramArgs = paramBoundDeclared.getTypeArguments();
                    final var argArgs = argDeclared.getTypeArguments();
                    for (int i = 0; i < Math.min(paramArgs.size(), argArgs.size()); i++) {
                        collectArgumentInferences(paramArgs.get(i), argArgs.get(i), inferenceMap);
                    }
                }
            }
        } else if (paramType instanceof DeclaredType paramDeclared
                && argType instanceof DeclaredType argDeclared) {
            if (paramDeclared.asTypeElement().getQualifiedName()
                    .equals(argDeclared.asTypeElement().getQualifiedName())) {
                final var paramArgs = paramDeclared.getTypeArguments();
                final var argArgs = argDeclared.getTypeArguments();
                for (int i = 0; i < Math.min(paramArgs.size(), argArgs.size()); i++) {
                    collectArgumentInferences(paramArgs.get(i), argArgs.get(i), inferenceMap);
                }
            }
        }
    }

    private void collectInferences(final TypeMirror paramType,
                                   final TypeMirror argType,
                                   final java.util.HashMap<String, TypeMirror> inferenceMap,
                                   final java.util.List<? extends TypeVariable> methodTypeVars) {
        if (paramType instanceof WildcardType wt && argType instanceof TypeVariable argTv) {
            if (wt.getBound() != null) {
                inferenceMap.putIfAbsent(argTv.asElement().getSimpleName(), wt.getBound());
            }
        } else if (paramType instanceof TypeVariable paramTv) {
            if (methodTypeVars.stream().anyMatch(mtv -> mtv.asElement().getSimpleName().equals(paramTv.asElement().getSimpleName()))) {
                if (!(argType instanceof TypeVariable)) {
                    inferenceMap.putIfAbsent(paramTv.asElement().getSimpleName(), argType);
                }
            }
        } else if (paramType instanceof DeclaredType paramDeclared
                && argType instanceof DeclaredType argDeclared) {
            if (paramDeclared.asTypeElement().getQualifiedName()
                    .equals(argDeclared.asTypeElement().getQualifiedName())) {
                final var paramArgs = paramDeclared.getTypeArguments();
                final var argArgs = argDeclared.getTypeArguments();
                for (int i = 0; i < Math.min(paramArgs.size(), argArgs.size()); i++) {
                    collectInferences(paramArgs.get(i), argArgs.get(i), inferenceMap, methodTypeVars);
                }
            }
        }
    }

    private TypeMirror substituteTypeVariables(final TypeMirror type,
                                               final java.util.Map<String, TypeMirror> map) {
        if (type instanceof TypeVariable tv) {
            final var replacement = map.get(tv.asElement().getSimpleName());
            return replacement != null ? replacement : type;
        } else if (type instanceof DeclaredType declaredType) {
            final var typeArgs = declaredType.getTypeArguments();
            if (typeArgs.isEmpty()) {
                return type;
            }
            final var newTypeArgs = typeArgs.stream()
                    .map(ta -> substituteTypeVariables(ta, map))
                    .toList();
            return types.getDeclaredType(
                    declaredType.asTypeElement(),
                    newTypeArgs.toArray(new TypeMirror[0])
            );
        }
        return type;
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

        if (!sourceType.isPrimitiveType() && targetType.isPrimitiveType()) {
            if (types.isBoxType(sourceType)) {
                final var unboxedType = types.unboxedType(sourceType);
                if (unboxedType != null) {
                    if (types.isSameType(unboxedType, targetType)) {
                        return true;
                    }
                    if (isWideningPrimitive(unboxedType, targetType)) {
                        return true;
                    }
                }
            }
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

            if (argType.isPrimitiveType() && paramType.isPrimitiveType()) {
                if (!types.isSameType(argType, paramType)) {
                    specificity += 0.5;
                }
            }

            if (!paramType.isPrimitiveType() && !argType.isPrimitiveType()
                    && paramType instanceof DeclaredType declaredParamType) {
                specificity += getClassDepth(declaredParamType);
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
            final var superclass = current.asTypeElement().getSuperclass();
            current = superclass instanceof DeclaredType dt ? dt : null;
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

        final var boxedClass = types.boxedClass((PrimitiveType) asPrimitiveType(source));
        final var boxedType = boxedClass.asType();

        // JLS 5.1.7 boxing + JLS 5.1.5 widening reference conversion:
        // the boxed type must be assignable to the target type.
        return types.isAssignable(boxedType, target);
    }

    private TypeMirror asPrimitiveType(final TypeMirror type) {
        if (type instanceof VariableType variableType) {
            return variableType.getInterferedType();
        } else {
            return type;
        }
    }

    private ApplicableMethod selectApplicableMethod(final List<ApplicableMethod> methods,
                                                        final DeclaredType searchType) {
        final var mostSpecific = chooseMostSpecificMethod(methods, searchType);
        return mostSpecific != null ? mostSpecific : methods.get(0);
    }

    private ApplicableMethod chooseMostSpecificMethod(
            final List<ApplicableMethod> applicableMethods,
            final DeclaredType searchType) {
        if (applicableMethods.isEmpty()) {
            return null;
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

        if (samePhase.isEmpty()) {
            return null;
        }

        // Leg de pre-deduplicatie lijst vast: alle kandidaten die dezelfde
        // (meest specifieke) fase delen. Wanneer deduplicatie die volledig
        // reduceert tot eén exemplaar, zijn alle oorspronkelijke kandidaten
        // identiek aan elkaar en is elk ervan een geldige (en evenwaardige)
        // keuze — alleen dan is het op de pre-dedup-lijst terugvallen correct.
        final var firstSamePhase = new ArrayList<>(samePhase);

        samePhase = deduplicate(samePhase);
        samePhase = new ArrayList<>(samePhase);
        samePhase.sort(Comparator.comparingDouble(ApplicableMethod::specificity));

        if (samePhase.isEmpty()) {
            // Alle kandidaten bleken duplicaten van elkaar. Dit kan gebeuren
            // wanneer een klasse meermaals in dezelfde compilatie wordt
            // binnengevoegd (bijv. gegenereerde bronnen die ook al op de
            // source-path staan), waardoor dezelfde methode meerdere keren
            // geregistreerd wordt. Alle kandidaten zijn dan identiek aan
            // elkaar, dus de eerste daarvan is een geldige keuze.
            firstSamePhase.sort(Comparator.comparingDouble(ApplicableMethod::specificity));
            return firstSamePhase.getFirst();
        }

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
                return null;
            }
        }

        return samePhase.getFirst();
    }

    /**
     * Verwijdert kandidaten die dezelfde methode voorstellen (zelfde declarerende
     * klasse, zelfde naam en zelfde (ge-erasede) parameterlijst) maar doorheen de
     * compilatie onder verschillende {@code MethodSymbol}-identiteiten zijn
     * geregistreerd. Zonder deze normalisatie zou een methode die per ongeluk
     * meermaals is toegevoegd als een ambigue overload worden beschouwd.
     */
    private List<ApplicableMethod> deduplicate(final List<ApplicableMethod> methods) {
        if (methods.size() < 2) {
            return methods;
        }

        final var result = new ArrayList<ApplicableMethod>();
        for (final var method : methods) {
            var duplicate = false;
            for (final var existing : result) {
                if (sameSignature(existing.method(), method.method())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(method);
            }
        }
        return result;
    }

    private boolean sameSignature(final ExecutableType left, final ExecutableType right) {
        final var leftSymbol = left.getMethodSymbol();
        final var rightSymbol = right.getMethodSymbol();

        if (!leftSymbol.getSimpleName().equals(rightSymbol.getSimpleName())) {
            return false;
        }

        final var leftOwner = leftSymbol.getEnclosingElement();
        final var rightOwner = rightSymbol.getEnclosingElement();
        if (leftOwner == null || rightOwner == null) {
            return leftOwner == rightOwner;
        }
        if (!TypePrinter.print(leftOwner.asType()).equals(TypePrinter.print(rightOwner.asType()))) {
            return false;
        }

        final var leftParams = left.getParameterTypes();
        final var rightParams = right.getParameterTypes();
        if (leftParams.size() != rightParams.size()) {
            return false;
        }

        for (var i = 0; i < leftParams.size(); i++) {
            final var leftParam = TypePrinter.print(leftParams.get(i));
            final var rightParam = TypePrinter.print(rightParams.get(i));
            if (!leftParam.equals(rightParam)) {
                return false;
            }
        }

        return true;
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
                final var parameterIsTypeVariable =
                        parameterTypes.get(i) instanceof TypeVariable;
                final var targetAllowsAnything = parameterIsTypeVariable
                        && !parameterTypes.get(i).isPrimitiveType();

                if (isImplicitlyTypedLambda(arguments.get(i))
                        || targetAllowsAnything) {
                    continue;
                }

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

        if (containsTypeVariable(sourceType) || containsTypeVariable(targetType)) {
            return true;
        }

        if (isAssignable(sourceType, targetType)) {
            return true;
        }

        if (isUnboxingCompatible(sourceType, targetType)) {
            return true;
        }

        return isBoxingCompatible(sourceType, targetType);
    }

    private boolean isAssignable(final TypeMirror sourceType, final TypeMirror targetType) {
        if (!(sourceType instanceof DeclaredType sourceDeclared)
                || !(targetType instanceof DeclaredType targetDeclared)) {
            return false;
        }

        final var targetElement = targetDeclared.asTypeElement();

        TypeMirror current = sourceDeclared;
        while (current instanceof DeclaredType currentDeclared) {
            final var currentElement = currentDeclared.asTypeElement();
            if (io.github.potjerodekool.nabu.compiler.resolve.types.IsSubType.sameClass(
                    currentElement,
                    targetElement)) {
                return true;
            }

            current = currentElement.getSuperclass();
        }

        for (final var interfaceType : sourceDeclared.asTypeElement().getInterfaces()) {
            if (interfaceType instanceof DeclaredType interfaceDeclared
                    && isAssignable(interfaceDeclared, targetDeclared)) {
                return true;
            }
        }

        return false;
    }

    private boolean containsTypeVariable(final TypeMirror type) {
        if (type == null) {
            return false;
        }

        if (type instanceof TypeVariable) {
            return true;
        }

        if (type instanceof DeclaredType declaredType) {
            return declaredType.getTypeArguments().stream()
                    .anyMatch(this::containsTypeVariable);
        }

        if (type instanceof ArrayType arrayType) {
            return containsTypeVariable(arrayType.getComponentType());
        }

        if (type instanceof WildcardType wildcardType) {
            return containsTypeVariable(wildcardType.getBound());
        }

        if (type instanceof VariableType variableType) {
            return containsTypeVariable(variableType.getInterferedType());
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

                final var arrayParamType = parameterTypes.get(fixedParamCount);
                final var varargType = ((ArrayType) arrayParamType).getComponentType();

                if (argumentTypes.size() == fixedParamCount + 1
                        && isLooselyCompatible(argumentTypes.get(fixedParamCount), arrayParamType)) {
                    isApplicable = true;
                } else {
                    for (int i = fixedParamCount; i < argumentTypes.size(); i++) {
                        if (!isLooselyCompatible(argumentTypes.get(i), varargType)) {
                            isApplicable = false;
                            break;
                        }
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

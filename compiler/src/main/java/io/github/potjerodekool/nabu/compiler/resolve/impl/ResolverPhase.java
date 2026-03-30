package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;
import io.github.potjerodekool.nabu.compiler.type.impl.UndetVarType;
import io.github.potjerodekool.nabu.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.*;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Pair;
import io.github.potjerodekool.nabu.util.Types;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ResolverPhase extends AbstractTreeVisitor<Object, Scope> {

    private final CompilerContextImpl compilerContext;
    private final ClassElementLoader loader;
    private final Types types;
    private final MethodResolver methodResolver;
    private final PhaseUtils phaseUtils;

    public ResolverPhase(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
        this.loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getTypes();
        this.methodResolver = compilerContext.getMethodResolver();
        this.phaseUtils = new PhaseUtils(compilerContext);
    }

    @Override
    public Object defaultAnswer(final Tree tree, final Scope param) {
        return tree;
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Scope scope) {
        return super.visitCompilationUnit(compilationUnit, createScope(compilationUnit));
    }

    private Scope createScope(final CompilationUnit compilationUnit) {
        return new GlobalScope(compilationUnit, compilerContext);
    }

    @Override
    public Object visitVariableType(final VariableTypeTree variableType, final Scope scope) {
        if (variableType.getType() == null) {
            variableType.setType(types.getVariableType(null));
        }
        return defaultAnswer(variableType, scope);
    }

    @Override
    public Object visitPackageDeclaration(final PackageDeclaration packageDeclaration, final Scope scope) {
        scope.setPackageElement(packageDeclaration.getPackageElement());
        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {

        classDeclaration.getModifiers().getAnnotations().forEach(annotation ->
                acceptTree(annotation, scope));

        final var clazz = (ClassSymbol) classDeclaration.getClassSymbol();

        if (clazz == null) {
            //In case of new class expression
            return null;
        }

        clazz.complete();

        compilerContext.getEnumUsageMap().registerClass(classDeclaration);

        final var classScope = new SymbolScope((DeclaredType) clazz.asType(), scope);

        final var interfaces = classDeclaration.getImplementing().stream()
                .map(it -> {
                    acceptTree(it, classScope);
                    return it.getType();
                }).toList();

        clazz.setInterfaces(interfaces);

        final var permits = classDeclaration.getPermits().stream()
                .map(permit -> {
                            acceptTree(permit, classScope);
                            return (Symbol) permit.getType()
                                    .asTypeElement();
                        }
                ).toList();

        clazz.setPermitted(permits);

        classDeclaration.getEnclosedElements()
                .forEach(enclosingElement ->
                        acceptTree(enclosingElement, classScope));

        final var typeParameters = classDeclaration.getTypeParameters().stream()
                .map(typeParameter -> (Element)
                        acceptTree(typeParameter, classScope))
                .map(Element::asType)
                .toList();

        final var declaredType = (CClassType) clazz.asType();
        declaredType.setTypeArguments(typeParameters);

        return null;
    }

    @Override
    public Object visitFunction(final Function function,
                                final Scope scope) {
        final var method = function.getMethodSymbol();
        final var functionScope = new FunctionScope(scope, method);

        if (!method.isStatic()) {
            final var type = method.getEnclosingElement().asType();

            final var thisVariable = new VariableSymbolBuilderImpl()
                    .kind(ElementKind.LOCAL_VARIABLE)
                    .simpleName(Constants.THIS)
                    .type(type)
                    .build();

            functionScope.define(thisVariable);
        }

        return super.visitFunction(function, functionScope);
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                   final Scope scope) {

        if (variableDeclaratorStatement.getValue() != null) {
            acceptTree(variableDeclaratorStatement.getValue(), scope);
        }

        acceptTree(variableDeclaratorStatement.getVariableType(), scope);

        var type = variableDeclaratorStatement.getVariableType().getType();

        if (type instanceof VariableType) {
            if (variableDeclaratorStatement.getValue() == null) {
                type = types.getErrorType("error");
            } else {
                final var interferedType = compilerContext.getTreeUtils().typeOf(variableDeclaratorStatement.getValue());
                type = types.getVariableType(interferedType);
            }
            variableDeclaratorStatement.getVariableType().setType(type);
            variableDeclaratorStatement.setType(type);
        }

        if (!(variableDeclaratorStatement.getKind() == Kind.FIELD
                || variableDeclaratorStatement.getKind() == Kind.ENUM_CONSTANT)) {

            if (variableDeclaratorStatement.getKind() == Kind.LOCAL_VARIABLE) {
                final var symbol = phaseUtils.createVariable(variableDeclaratorStatement);
                variableDeclaratorStatement.getName().setSymbol(symbol);
                scope.define(symbol);
            } else if (variableDeclaratorStatement.getKind() == Kind.PARAMETER) {
                var symbol = variableDeclaratorStatement.getName().getSymbol();

                if (symbol == null) {
                    //Should only occur with lambda parameters.
                    symbol = phaseUtils.createVariable(variableDeclaratorStatement);
                    variableDeclaratorStatement.getName().setSymbol(symbol);
                    scope.define(symbol);
                }
            }
        }

        final var symbol = (VariableSymbol) variableDeclaratorStatement.getName().getSymbol();

        if (symbol != null) {
            //TODO remove non null check. When annotation can't be resolved, it should be an error type.
            final var annotations = variableDeclaratorStatement.getAnnotations().stream()
                    .map(annotation -> (CompoundAttribute)
                            acceptTree(annotation, scope))
                    .filter(Objects::nonNull)
                    .toList();

            symbol.setAnnotations(annotations);
        }

        return null;
    }


    @Override
    public Object visitCastExpression(final CastExpressionTree castExpressionTree,
                                      final Scope scope) {
        acceptTree(castExpressionTree.getExpression(), scope);
        acceptTree(castExpressionTree.getTargetType(), scope);
        return defaultAnswer(castExpressionTree, scope);
    }

    @Override
    public Object visitWildCardExpression(final WildcardExpressionTree wildCardExpression, final Scope scope) {
        final TypeMirror type;

        type = switch (wildCardExpression.getBoundKind()) {
            case UNBOUND -> types.getWildcardType(null, null);
            case EXTENDS -> {
                final var extendsBound = (TypeMirror) acceptTree(wildCardExpression.getBound(), scope);
                yield types.getWildcardType(extendsBound, null);

            }
            case SUPER -> {
                final var superBound = (TypeMirror) acceptTree(wildCardExpression, scope);
                yield types.getWildcardType(null, superBound);
            }
        };

        wildCardExpression.setType(type);
        return defaultAnswer(wildCardExpression, scope);
    }

    public Object visitIfStatement(final IfStatementTree ifStatementTree, final Scope scope) {
        final var builder = ifStatementTree.builder();
        final var expression = (ExpressionTree) acceptTree(ifStatementTree.getExpression(), scope);
        builder.expression(expression);

        acceptTree(ifStatementTree.getThenStatement(), scope);

        if (ifStatementTree.getElseStatement() != null) {
            acceptTree(ifStatementTree.getElseStatement(), scope);
        }

        return builder.build();
    }

    public Object visitBinaryExpression(final BinaryExpressionTree binaryExpression, final Scope scope) {
        super.visitBinaryExpression(binaryExpression, scope);
        final var leftType = compilerContext.getTreeUtils().typeOf(binaryExpression.getLeft());
        final var rightType = compilerContext.getTreeUtils().typeOf(binaryExpression.getRight());

        if (leftType != null
                && rightType != null) {

            if (!types.isSameType(leftType, rightType)) {
                final var transformed = transformBinaryExpression(binaryExpression, leftType, rightType);
                final var module = scope.findModuleElement();
                final var stringType = loader.loadClass(module, Constants.STRING).asType();

                if (types.isSameType(stringType, leftType)
                        || types.isSameType(stringType, rightType)) {
                    binaryExpression.setType(leftType);
                } else {
                    binaryExpression.setType(leftType);
                }
            } else {
                binaryExpression.setType(leftType);
            }
        }

        return defaultAnswer(binaryExpression, scope);
    }



    @Override
    public Object visitTypeIdentifier(final TypeApplyTree typeIdentifier,
                                      final Scope scope) {
        var type = typeIdentifier.getType();
        final var clazz = typeIdentifier.getClazz();
        final var name = TreeUtils.getClassName(clazz);

        if (type == null) {
            type = resolveType(name, scope);
        }

        if (type == null) {
            type = compilerContext.getTypes().getErrorType(name);
        }

        if (typeIdentifier.getTypeParameters() != null) {
            final var typeParams = typeIdentifier.getTypeParameters().stream()
                    .map(typeParam -> acceptTree(typeParam, scope))
                    .map(typeParam -> (ExpressionTree) typeParam)
                    .map(ExpressionTree::getType)
                    .toArray(TypeMirror[]::new);

            type = types.getDeclaredType(
                    type.asTypeElement(),
                    typeParams
            );
        }

        typeIdentifier.setType(type);

        return defaultAnswer(typeIdentifier, scope);
    }

    private TypeMirror resolveType(final String name,
                                   final Scope scope) {
        TypeMirror type = scope.resolveType(name);

        if (type == null) {
            final var resolvedClass = loader.loadClass(
                    scope.findModuleElement(),
                    name
            );

            if (resolvedClass != null) {
                type = resolvedClass.asType();
            }
        }

        return type;
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                        final Scope scope) {
        /*
        //lambdaExpression.getVariables().forEach(variable -> acceptTree(variable, scope));
        final var lambdaScope = new LocalScope(scope);
        acceptTree(lambdaExpression.getBody(), lambdaScope);
        */

        final var parameterTypes = lambdaExpression.getVariables().stream()
                .map(variable -> {
                    var variableType = variable.getType();
                    if (variableType == null || variableType.isError()) {
                        variableType = types.getUnknownType();
                    }

                    return variableType;
                }).toList();

        final var partialType = new UndetVarType(
                new CMethodType(
                        null,
                        null,
                        List.of(),
                        null,
                        parameterTypes,
                        List.of()
                )
        );
        lambdaExpression.setType(partialType);

        return defaultAnswer(lambdaExpression, scope);
    }


    private void postResolve(final LambdaExpressionTree lambdaExpression,
                             final TypeMirror parameterType,
                             final Scope scope) {
        final var method = parameterType.asTypeElement().findFunctionalMethod();
        final var member = (ExecutableType) types.asMemberOf(
                (DeclaredType) parameterType,
                method
        );

        final var lambdaScope = new LocalScope(scope);
        final var variables = lambdaExpression.getVariables();

        lambdaExpression.setLambdaMethodType(member);
        variables.forEach(variable -> acceptTree(variable, lambdaScope));

        acceptTree(lambdaExpression.getBody(), lambdaScope);
    }

    @Override
    public Object visitWhileStatement(final WhileStatementTree whileStatement, final Scope scope) {
        super.visitWhileStatement(whileStatement, scope);
        return defaultAnswer(whileStatement, scope);
    }

    @Override
    public Object visitDoWhileStatement(final DoWhileStatementTree doWhileStatement, final Scope scope) {
        super.visitDoWhileStatement(doWhileStatement, scope);
        return defaultAnswer(doWhileStatement, scope);
    }

    private BinaryExpressionTree transformBinaryExpression(final BinaryExpressionTree binaryExpression,
                                                           final TypeMirror leftType,
                                                           final TypeMirror rightType) {
        if (leftType instanceof PrimitiveType primitiveType) {
            if (rightType instanceof PrimitiveType rightPrimitiveType) {
                return transformBinaryExpression(
                        binaryExpression,
                        primitiveType,
                        rightPrimitiveType
                );
            } else {
                return binaryExpression;
            }
        }

        return binaryExpression;
    }

    private BinaryExpressionTree transformBinaryExpression(final BinaryExpressionTree binaryExpression,
                                                           final PrimitiveType leftType,
                                                           final PrimitiveType rightType) {
        if (leftType.getKind() == TypeKind.BYTE) {
            return binaryExpression;
        } else if (leftType.getKind() == TypeKind.LONG) {
            if (rightType.getKind() == TypeKind.INT) {
                final var rightLiteral = (LiteralExpressionTree) binaryExpression.getRight();
                final var literal = (Integer) rightLiteral.getLiteral();

                rightLiteral.setLiteral(literal.longValue());
                rightLiteral.setLiteralKind(LiteralExpressionTree.Kind.LONG);
                rightLiteral.setType(types.getPrimitiveType(TypeKind.LONG));

                return binaryExpression;
            }
        }

        return binaryExpression;
    }

    @Override
    public Object visitAnnotation(final AnnotationTree annotationTree, final Scope scope) {
        acceptTree(annotationTree.getName(), scope);
        var annotationType = (DeclaredType) annotationTree.getName().getType();

        if (annotationType == null) {
            annotationType = compilerContext.getTypes()
                    .getErrorType(annotationTree.getName().getName());
            annotationTree.getName().setType(annotationType);
        }

        final var annotationScope = new SymbolScope(annotationType, scope);

        final var values = annotationTree.getArguments().stream()
                .map(it -> (Pair<ExecutableElement, AnnotationValue>) acceptTree(it, annotationScope))
                .filter(it -> it.first() != null)
                .collect(Collectors.toMap(
                        Pair::first,
                        Pair::second
                ));

        return AnnotationBuilder.createAnnotation(
                annotationType,
                values
        );
    }

    @Override
    public Object visitPrimitiveType(final PrimitiveTypeTree primitiveType,
                                     final Scope scope) {
        final var type  = switch (primitiveType.getKind()) {
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case CHAR -> types.getPrimitiveType(TypeKind.CHAR);
            case BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case INT -> types.getPrimitiveType(TypeKind.INT);
            case FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case LONG -> types.getPrimitiveType(TypeKind.LONG);
            case DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);
            case VOID -> types.getNoType(TypeKind.VOID);
        };

        primitiveType.setType(type);
        return defaultAnswer(primitiveType, scope);
    }

    @Override
    public Object visitSwitchStatement(final SwitchStatement switchStatement, final Scope scope) {
        acceptTree(switchStatement.getSelector(), scope);
        final var switchScope = new SwitchScope(
                switchStatement.getSelector().getSymbol(),
                scope
        );
        switchStatement.getCases().forEach(caseStatement -> acceptTree(caseStatement, switchScope));
        return defaultAnswer(switchStatement, switchScope);
    }

    @Override
    public Object visitArrayAccess(final ArrayAccessExpressionTree arrayAccessExpressionTree, final Scope scope) {
        acceptTree(arrayAccessExpressionTree.getExpression(), scope);
        acceptTree(arrayAccessExpressionTree.getIndex(), scope);
        return defaultAnswer(arrayAccessExpressionTree, scope);
    }

    @Override
    public Object visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement, final Scope scope) {
        acceptTree(enhancedForStatement.getExpression(), scope);

        if (enhancedForStatement.getLocalVariable().getVariableType() instanceof VariableTypeTree variableTypeTree) {
            final DeclaredType expressionType;

            final var expression = enhancedForStatement.getExpression();
            final var symbol = expression.getSymbol();

            if (symbol != null) {
                expressionType = asDeclaredType(symbol.asType());
            } else {
                expressionType = asDeclaredType(expression.getType());
            }

            final var type = expressionType.getTypeArguments().getFirst();
            variableTypeTree.setType(type);
        }

        acceptTree(enhancedForStatement.getLocalVariable(), scope);
        acceptTree(enhancedForStatement.getStatement(), scope);
        return defaultAnswer(enhancedForStatement, scope);
    }

    @Override
    public Object visitTypeParameter(final TypeParameterTree typeParameterTree,
                                     final Scope scope) {
        final var currentClass = scope.getCurrentClass();

        var typeBound = typeParameterTree.getTypeBound().stream()
                .map(it -> (TypeMirror) acceptTree(it, scope))
                .toList();

        final var upperBound = switch (typeBound.size()) {
            case 0 -> loader.loadClass(
                    findModuleElement(currentClass),
                    Constants.OBJECT
            ).asType();
            case 1 -> typeBound.getFirst();
            default -> types.getIntersectionType(typeBound);
        };

        return types.getTypeVariable(
                typeParameterTree.getIdentifier().getName(),
                upperBound,
                null
        ).asElement();
    }

    private DeclaredType asDeclaredType(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            return declaredType;
        } else {
            final var variableType = (VariableType) typeMirror;
            return (DeclaredType) variableType.getInterferedType();
        }
    }

    private ModuleElement findModuleElement(final Element element) {
        if (element == null) {
            return null;
        }
        if (element instanceof PackageElement packageElement) {
            return packageElement.getModuleSymbol();
        } else {
            return findModuleElement(element.getEnclosingElement());
        }
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                             final Scope scope) {
        final var selected = fieldAccessExpression.getSelected();
        acceptTree(selected, scope);

        final var varElement = TreeUtils.getSymbol(selected);

        if (varElement != null) {
            final var varType = varElement.asType();
            final DeclaredType declaredType = asDeclaredType(varType);
            final var symbolScope = new SymbolScope(
                    declaredType,
                    scope.getGlobalScope()
            );
            acceptTree(fieldAccessExpression.getField(), symbolScope);
        } else if (selected.getType() != null) {
            final DeclaredType declaredType = asDeclaredType(selected.getType());
            final var classScope = new ClassScope(
                    declaredType,
                    null,
                    scope.getCompilationUnit(),
                    compilerContext
            );
            acceptTree(fieldAccessExpression.getField(), classScope);
        }

        fieldAccessExpression.setType(fieldAccessExpression.getField().getType());
        return defaultAnswer(fieldAccessExpression, scope);
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final Scope scope) {
        var type = identifier.getType();

        if (type == null) {
            type = resolveType(identifier.getName(), scope);
        }

        if (type != null) {
            identifier.setType(type);
            identifier.setSymbol(null);
        } else {
            final var symbol = scope.resolve(identifier.getName());

            if (symbol != null) {
                identifier.setSymbol(symbol);
            } else if (identifier.getSymbol() == null) {
                identifier.setSymbol(
                        compilerContext.getElementBuilders()
                                .createErrorSymbol(identifier.getName())
                );
                identifier.setType(types.getErrorType(identifier.getName()));
            }
        }

        return defaultAnswer(identifier, scope);
    }


    @Override
    public Object visitReturnStatement(final ReturnStatementTree returnStatement,
                                       final Scope scope) {
        final var expression = returnStatement.getExpression();

        if (expression instanceof LambdaExpressionTree lambdaExpression) {
            final var method = scope.getCurrentMethod();
            final var type = method.getReturnType();
            lambdaExpression.setType(type);
        }

        return super.visitReturnStatement(returnStatement, scope);
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                        final Scope scope) {
        final var methodSelector = methodInvocation.getMethodSelector();
        acceptTree(methodSelector, scope);

        methodInvocation.getArguments().forEach(arg ->
                acceptTree(arg, scope));
        methodInvocation.getTypeArguments().forEach(typeArgument ->
                acceptTree(typeArgument, scope));

        final var resolvedMetho0dTypeOptional = methodResolver.resolveMethod(methodInvocation, scope.getCurrentElement(), scope);

        if (resolvedMetho0dTypeOptional.isEmpty()) {
            methodResolver.resolveMethod(methodInvocation, scope.getCurrentElement(), scope);
        }

        resolvedMetho0dTypeOptional.ifPresent(resolvedMethodType -> {
            methodSelector.setType(resolvedMethodType.getOwner().asType());
            methodInvocation.setMethodType(resolvedMethodType);
            final var boxer = compilerContext.getArgumentBoxer();
            boxer.boxArguments(methodInvocation);
        });

        if (resolvedMetho0dTypeOptional.isPresent()) {
            final var arguments = methodInvocation.getArguments();
            final var parameterTypes = methodInvocation.getMethodType().getParameterTypes();

            for (var i = 0; i < arguments.size(); i++) {
                final var argument = arguments.get(i);
                final var parameterType = parameterTypes.get(i);

                if (argument instanceof LambdaExpressionTree lambdaExpression) {
                    postResolve(lambdaExpression, parameterType, scope);
                }
            }
        }

        return null;
    }

    @Override
    public Object visitLiteralExpression(final LiteralExpressionTree literalExpression,
                                         final Scope scope) {
        final var loader = compilerContext.getClassElementLoader();
        final var types =  compilerContext.getTypes();

        final TypeMirror type = switch (literalExpression.getLiteralKind()) {
            case INTEGER -> types.getPrimitiveType(TypeKind.INT);
            case LONG -> types.getPrimitiveType(TypeKind.LONG);
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case STRING -> loader.loadClass(scope.findModuleElement(), Constants.STRING).asType();
            case NULL -> types.getNullType();
            case CLASS -> loader.loadClass(scope.findModuleElement(), Constants.CLAZZ).asType();
            case BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);
            case CHAR -> types.getPrimitiveType(TypeKind.CHAR);
        };

        literalExpression.setType(type);

        return null;
    }

    @Override
    public Object visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression, final Scope scope) {
        acceptTree(instanceOfExpression.getExpression(), scope);
        acceptTree(instanceOfExpression.getTypeExpression(), scope);
        return null;
    }

    @Override
    public Object visitArrayType(final ArrayTypeTree arrayTypeTree, final Scope scope) {
        acceptTree(arrayTypeTree.getComponentType(), scope);
        final var componentType = arrayTypeTree.getComponentType().getType();
        final var types = compilerContext.getTypes();
        final var arrayType = types.getArrayType(componentType);
        arrayTypeTree.setType(arrayType);
        return null;
    }

    @Override
    public Object visitConstantCaseLabel(final ConstantCaseLabel constantCaseLabel, final Scope scope) {
        final var switchScope = (SwitchScope) scope;
        final var selectorElement = switchScope.getSelectorElement();

        if (selectorElement instanceof VariableElement variableElement
                && variableElement.asType().asElement() instanceof TypeElement typeElement
                && typeElement.getKind() == ElementKind.ENUM
                && constantCaseLabel.getExpression() instanceof IdentifierTree identifierTree) {

            final var name = identifierTree.getName();

            ElementFilter.enumConstantByName(typeElement, name)
                    .ifPresent(enumConstant -> {
                        identifierTree.setSymbol(enumConstant);

                        final var currentClass = scope.getCurrentClass();
                        compilerContext.getEnumUsageMap().registerEnumUsage(currentClass, enumConstant);
                    });

            return defaultAnswer(constantCaseLabel, scope);
        }

        return super.visitConstantCaseLabel(constantCaseLabel, scope);
    }

    @Override
    public Object visitAssignment(final AssignmentExpressionTree assignmentExpressionTree, final Scope scope) {
        final var left = (IdentifierTree) assignmentExpressionTree.getLeft();
        final var methodName = left.getName();

        final var currentClass = scope.getCurrentClass();

        final var resolvedMethod = ElementFilter.methodsIn(currentClass.getEnclosedElements()).stream()
                .filter(method -> method.getSimpleName().equals(methodName))
                .filter(method -> method.getParameters().isEmpty())
                .findFirst()
                .orElse(null);

        acceptTree(assignmentExpressionTree.getRight(), scope);

        final var right = assignmentExpressionTree.getRight();
        final Attribute attributeValue;

        if (right instanceof LiteralExpressionTree literalExpressionTree) {
            attributeValue = AnnotationBuilder.createConstantValue(literalExpressionTree.getLiteral());
        } else if (right instanceof NewArrayExpression newArrayExpression) {
            var type = (ArrayType) newArrayExpression.getType();
            final TypeMirror componentType;

            if (type != null) {
                componentType = type.getComponentType();
            } else if (!newArrayExpression.getElements().isEmpty()) {
                componentType = newArrayExpression.getElements().getFirst().getType();
            } else {
                componentType = null;
            }

            final var values = newArrayExpression.getElements().stream()
                    .map(this::createAnnotationValue)
                    .toList();
            attributeValue = AnnotationBuilder.createArrayValue(
                    componentType,
                    values
            );
        } else {
            acceptTree(right, scope);
            attributeValue = createAttribute(right.getType());
        }

        left.setSymbol(resolvedMethod);

        return new Pair<>(
                resolvedMethod,
                attributeValue
        );
    }

    private Attribute createAttribute(final TypeMirror type) {
        if (type instanceof DeclaredType declaredType) {
            if (Constants.CLAZZ.equals(declaredType.asTypeElement().getQualifiedName())) {
                return new CClassAttribute(type);
            }
        }

        throw new TodoException();
    }

    private AnnotationValue createAnnotationValue(final ExpressionTree expressionTree) {
        if (expressionTree instanceof LiteralExpressionTree literalExpressionTree) {
            return AnnotationBuilder.createConstantValue(literalExpressionTree.getLiteral());
        }
        throw new TodoException();
    }
}
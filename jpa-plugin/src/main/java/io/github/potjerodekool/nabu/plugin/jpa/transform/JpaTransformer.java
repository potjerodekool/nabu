package io.github.potjerodekool.nabu.plugin.jpa.transform;

import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.scope.*;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.transform.spi.CodeTransformer;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Types;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.potjerodekool.nabu.plugin.jpa.transform.Helper.createBuilderCall;
import static io.github.potjerodekool.nabu.plugin.jpa.transform.Helper.resolvePathType;
import static io.github.potjerodekool.nabu.tree.TreeUtils.getSymbol;

/**
 * Transform JPA dsl to normal code.
 */
public class JpaTransformer extends AbstractTreeVisitor<Object, Scope> implements CodeTransformer {

    private static final String CRITERIA_BUILDER_CLASS = "jakarta.persistence.criteria.CriteriaBuilder";
    private static final String PATH_CLASS = "jakarta.persistence.criteria.Path";
    private static final String JOIN_CLASS = "jakarta.persistence.criteria.Join";

    private final CompilerContext compilerContext;
    private final ClassElementLoader loader;
    private final Types types;

    private final Map<Tag, String> operatorMapping = Map.of(
            Tag.EQ, "equal",
            Tag.NE, "notEqual",
            Tag.LT, "lessThan",
            Tag.GT, "greaterThan",
            Tag.LE, "lessThanOrEqualTo",
            Tag.GE, "greaterThanOrEqualTo"
    );

    public JpaTransformer(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
        loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getClassElementLoader().getTypes();
    }

    @Override
    public void transform(final CompilationUnit compilationUnit) {
        final var globalScope = new GlobalScope(compilationUnit, compilerContext);
        compilationUnit.accept(this, globalScope);
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration, final Scope scope) {
        final var clazz = classDeclaration.getClassSymbol();

        if (clazz == null) {
            return null;
        }

        final var classScope = new ClassScope(
                clazz.asType(),
                scope,
                null,
                loader
        );

        final var symbolScope = new SymbolScope((DeclaredType) clazz.asType(), classScope);
        classDeclaration.getEnclosedElements()
                .forEach(enclosingElement -> enclosingElement.accept(this, symbolScope));
        return null;
    }

    @Override
    public Object visitFunction(final Function function, final Scope scope) {
        final var method = function.getMethodSymbol();
        final var functionScope = new FunctionScope(scope, method);
        return super.visitFunction(function, functionScope);
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocationTree methodInvocation, final Scope param) {
        return methodInvocation;
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambdaExpression, final Scope scope) {
        final var classType = (DeclaredType) lambdaExpression.getType();
        final var classElement = (TypeElement) classType.asElement();
        final var functionalMethod = classElement.findFunctionalMethod();
        final var methodType = (ExecutableType) functionalMethod.asType();
        final var argumentTypes = methodType.getParameterTypes();
        final var variables = lambdaExpression.getVariables();

        for (int argIndex = 0; argIndex < argumentTypes.size(); argIndex++) {
            final var variable = variables.get(argIndex);
            final var parameter = variable.getName().getSymbol();
            scope.define(parameter);
        }

        lambdaExpression.getVariables().forEach(variable -> variable.accept(this, scope));
        final var newBody = (StatementTree) lambdaExpression.getBody().accept(this, scope);
        lambdaExpression.body(newBody);
        return lambdaExpression;
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                             final Scope scope) {
        boolean changed = false;

        final var module = scope.findModuleElement();
        var selected = fieldAccessExpression.getSelected();
        var field = fieldAccessExpression.getField();
        final var newTarget = (ExpressionTree) selected.accept(this, scope);

        if (newTarget != selected) {
            fieldAccessExpression.selected(newTarget);
            selected = newTarget;
            changed = true;
        }

        final Scope targetScope;

        if (selected.getSymbol() instanceof VariableElement variableElement) {
            targetScope = new SymbolScope(
                    resolveSearchType(asDeclaredType(variableElement.asType()), scope),
                    scope.getGlobalScope()
            );
        } else if (selected.getType() != null) {
            targetScope = new SymbolScope(
                    resolveSearchType((DeclaredType) selected.getType(), scope),
                    scope.getGlobalScope()
            );
        } else {
            targetScope = scope;
        }

        final var newField = (IdentifierTree) field.accept(this, targetScope);

        if (changed
                || newField != field) {
            System.out.println();
        }

        final var symbol = getSymbol(selected);

        if (symbol instanceof VariableElement variableElement) {
            final var variableType = variableElement.asType();

            final var pathType = resolvePathType(loader, scope);
            final var isPath = types.isSubType(variableType, pathType);

            if (isPath) {
                final var literal = TreeMaker.literalExpressionTree(newField.getName(), -1, -1);
                final var stringType = loader.loadClass(module, ClassNames.STRING_CLASS_NAME).asType();
                literal.setType(stringType);

                final var targetType = resolveVariableType(variableElement);
                final var fieldType = resolveFieldType(targetType, newField.getName());

                final List<IdentifierTree> typeArguments;

                if (fieldType instanceof DeclaredType declaredType) {
                    final var clazz = (TypeElement) declaredType.asElement();
                    final var clazzName = clazz.getQualifiedName();
                    final var typeArg = IdentifierTree.create(clazzName);
                    typeArg.setType(declaredType);
                    typeArguments = List.of(typeArg);
                } else {
                    typeArguments = List.of();
                }

                final var methodInvocation = TreeMaker.methodInvocationTree(
                        TreeMaker.fieldAccessExpressionTree(
                                selected,
                                IdentifierTree.create("get"),
                                -1,
                                -1
                        ),
                        typeArguments,
                        List.of(literal),
                        -1,
                        -1
                );

                final var methodTypeOptional = compilerContext.getMethodResolver().resolveMethod(methodInvocation);

                methodTypeOptional.ifPresent(methodType -> {
                    methodInvocation.getMethodSelector().setType(methodType.getOwner().asType());
                    methodInvocation.setMethodType(methodType);
                });

                return methodInvocation;
            }
        }

        return fieldAccessExpression;
    }

    private DeclaredType resolveSearchType(final DeclaredType declaredType,
                                           final Scope scope) {
        final var pathType = compilerContext.getClassElementLoader().loadClass(scope.findModuleElement(), PATH_CLASS).asType();
        final var types = compilerContext.getClassElementLoader().getTypes();

        if (types.isSubType(declaredType, pathType)) {
            if (isJoinType(declaredType)) {
                return (DeclaredType) declaredType.getTypeArguments().getLast();
            } else {
                return (DeclaredType) declaredType.getTypeArguments().getFirst();
            }
        } else {
            return declaredType;
        }
    }

    private boolean isJoinType(final DeclaredType declaredType) {
        final var classSymbol = (TypeElement) declaredType.asElement();
        return JOIN_CLASS.equals(classSymbol.getQualifiedName());
    }

    private DeclaredType asDeclaredType(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            return declaredType;
        } else if (typeMirror instanceof VariableType variableType) {
            return asDeclaredType(variableType.getInterferedType());
        } else {
            throw new UnsupportedOperationException();
        }
    }

    private DeclaredType resolveVariableType(final VariableElement variableElement) {
        final var declaredType = (DeclaredType) variableElement.asType();
        return (DeclaredType) declaredType.getTypeArguments().getLast();
    }

    private TypeMirror resolveFieldType(final DeclaredType declaredType,
                                        final String fieldName) {
        TypeMirror fieldType = null;

        final var clazz = (TypeElement) declaredType.asElement();

        final var fieldTypeOptional = ElementFilter.fieldsIn(clazz.getEnclosedElements()).stream()
                .filter(it -> it.getSimpleName().equals(fieldName))
                .map(VariableElement::asType)
                .findFirst();

        if (fieldTypeOptional.isPresent()) {
            fieldType = fieldTypeOptional.get();
        } else {
            final var superType = (DeclaredType) clazz.getSuperclass();

            if (superType != null) {
                fieldType = resolveFieldType(superType, fieldName);
            }
        }

        if (fieldType instanceof PrimitiveType primitiveType) {
            fieldType = types.boxedClass(primitiveType).asType();
        }

        return fieldType;
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpressionTree binaryExpression, final Scope scope) {
        final var newLeft = (ExpressionTree) binaryExpression.getLeft().accept(this, scope);
        final var newRight = (ExpressionTree) binaryExpression.getRight().accept(this, scope);
        final var changed = newLeft != binaryExpression.getLeft()
                || newRight != binaryExpression.getRight();

        if (changed) {
            final var newExpression = binaryExpression.builder()
                    .left(newLeft)
                    .right(newRight)
                    .build();

            final var pathType = resolvePathType(loader, scope);

            if (types.isSubType(newLeft.getType(), pathType)) {
                return transformBinaryExpression(newExpression, scope);
            }

            return transformAndOr(newExpression, scope);
        }

        return transformAndOr(binaryExpression, scope);
    }

    private ExpressionTree transformAndOr(final BinaryExpressionTree binaryExpression,
                                          final Scope scope) {
        final var builderVariableOptional = findBuilderVariable(scope);

        if (builderVariableOptional.isEmpty()) {
            return binaryExpression;
        }

        final var builderVariable = builderVariableOptional.get();

        final var tag = binaryExpression.getTag();

        final String operatorMethodName;

        if (tag == Tag.AND) {
            operatorMethodName = "and";
        } else if (tag == Tag.OR) {
            operatorMethodName = "or";
        } else {
            return binaryExpression;
        }

        final var target = IdentifierTree.create(builderVariable.getSimpleName());
        target.setSymbol(builderVariable);

        return createBuilderCall(
                compilerContext,
                target,
                operatorMethodName,
                binaryExpression.getLeft(),
                binaryExpression.getRight()
        );

    }

    private ExpressionTree transformBinaryExpression(final BinaryExpressionTree binaryExpression, final Scope scope) {
        final var methodName = resolveMethodName(binaryExpression.getTag());
        final var builderVariableOptional = findBuilderVariable(scope);

        if (builderVariableOptional.isEmpty()) {
            return binaryExpression;
        }

        final var builderVariable = builderVariableOptional.get();
        String builderName = builderVariable.getSimpleName();

        final var builderIdentifier = IdentifierTree.create(builderName);
        builderIdentifier.setSymbol(builderVariable);

        return createBuilderCall(
                compilerContext,
                builderIdentifier,
                methodName,
                binaryExpression.getLeft(),
                binaryExpression.getRight()
        );
    }

    private Optional<VariableElement> findBuilderVariable(final Scope scope) {
        final var criteriaBuilderType = loader.loadClass(scope.findModuleElement(), CRITERIA_BUILDER_CLASS).asType();
        return findBuilderVariable(scope, criteriaBuilderType);
    }

    private Optional<VariableElement> findBuilderVariable(final Scope scope,
                                                          final TypeMirror criteriaBuilderType) {
        final var variableOptional = scope.locals().stream()
                .map(scope::resolve)
                .filter(symbol -> symbol instanceof VariableElement)
                .map(variable -> (VariableElement) variable)
                .filter(variableElement -> {
                    final var varType = variableElement.asType();
                    return types.isSameType(criteriaBuilderType, varType);
                }).findFirst();

        if (variableOptional.isPresent()) {
            return variableOptional;
        } else {
            final var parentScope = scope.getParent();
            if (parentScope != null) {
                return findBuilderVariable(parentScope, criteriaBuilderType);
            } else {
                return Optional.empty();
            }
        }
    }

    private String resolveMethodName(final Tag tag) {
        return operatorMapping.get(tag);
    }

    @Override
    public Object visitExpressionStatement(final ExpressionStatementTree expressionStatement, final Scope scope) {
        final var newExpression = (ExpressionTree) expressionStatement.getExpression().accept(this, scope);

        if (newExpression != expressionStatement.getExpression()) {
            return expressionStatement.builder()
                    .expression(newExpression)
                    .build();
        }

        return expressionStatement;
    }

    @Override
    public Object visitBlockStatement(final BlockStatementTree blockStatement, final Scope scope) {
        final var newStatements = blockStatement.getStatements().stream()
                .map(statement -> {
                    final var result = statement.accept(this, scope);

                    if (!(result instanceof StatementTree)) {
                        throw new IllegalArgumentException();
                    }

                    return (StatementTree) result;
                })
                .toList();

        return blockStatement.builder()
                .statements(newStatements)
                .build();
    }

    private Tree accept(final Tree tree,
                        final Scope scope) {
        return tree != null ?
                (Tree) tree.accept(this, scope)
                : null;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final Scope scope) {
        final var newType = (ExpressionTree) variableDeclaratorStatement.getVariableType().accept(this, scope);
        final var newIdentifier = (IdentifierTree) variableDeclaratorStatement.getName().accept(this, scope);
        final var newValue = accept(variableDeclaratorStatement.getValue(), scope);

        final var newVariableDeclaratorStatement = variableDeclaratorStatement.builder()
                .variableType(newType)
                .name(newIdentifier)
                .value(newValue)
                .build();

        final TypeMirror type;

        if (newVariableDeclaratorStatement.getValue() != null
                && variableDeclaratorStatement.getVariableType() instanceof VariableTypeTree) {
            type = compilerContext.getTreeUtils().typeOf(newVariableDeclaratorStatement.getValue());
        } else {
            type = compilerContext.getTreeUtils().typeOf(newType);
        }

        final var kind = ElementKind.valueOf(variableDeclaratorStatement.getKind().name());
        final var flags = variableDeclaratorStatement.getFlags();

        final var oldSymbol = variableDeclaratorStatement.getName()
                .getSymbol();

        final var enclosingElement = oldSymbol != null
                ? oldSymbol.getEnclosingElement()
                : null;

        final var varElement = this.compilerContext.getElementBuilders().variableElementBuilder()
                .kind(kind)
                .simpleName(newIdentifier.getName())
                .type(type)
                .flags(flags)
                .enclosingElement(enclosingElement)
                .build();

        final var name = variableDeclaratorStatement.getName();
        name.setSymbol(varElement);
        scope.define(varElement);

        return newVariableDeclaratorStatement;
    }

    @Override
    public Object visitUnaryExpression(final UnaryExpressionTree unaryExpression, final Scope scope) {
        final var newExpression = (ExpressionTree) unaryExpression.getExpression().accept(this, scope);

        if (unaryExpression.getTag() != Tag.NOT) {
            return unaryExpression;
        }

        final var builderVariableOptional = findBuilderVariable(scope);

        if (builderVariableOptional.isEmpty()) {
            return unaryExpression;
        }

        final var builderVariable = builderVariableOptional.get();

        final var target = IdentifierTree.create(builderVariable.getSimpleName());
        target.setSymbol(builderVariable);

        return createBuilderCall(
                compilerContext,
                target,
                "not",
                newExpression
        );
    }

    @Override
    public Object visitLiteralExpression(final LiteralExpressionTree literalExpression, final Scope scope) {
        super.visitLiteralExpression(literalExpression, scope);
        final var module = scope.findModuleElement();

        final TypeMirror type = switch (literalExpression.getLiteralKind()) {
            case INTEGER -> types.getPrimitiveType(TypeKind.INT);
            case LONG -> types.getPrimitiveType(TypeKind.LONG);
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case STRING -> loader.loadClass(module, ClassNames.STRING_CLASS_NAME).asType();
            case NULL -> types.getNullType();
            case CLASS -> loader.loadClass(module, ClassNames.CLASS_CLASS_NAME).asType();
            case BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);
            case CHAR -> types.getPrimitiveType(TypeKind.CHAR);
        };

        literalExpression.setType(type);

        return literalExpression;
    }

    @Override
    public Object visitReturnStatement(final ReturnStatementTree returnStatement,
                                       final Scope scope) {
        if (returnStatement.getExpression() == null) {
            return returnStatement;
        }

        final var newExpression = (ExpressionTree) returnStatement.getExpression().accept(this, scope);

        if (newExpression != returnStatement.getExpression()) {
            return returnStatement.builder()
                    .expression(newExpression)
                    .build();
        }

        return returnStatement;
    }

    @Override
    public Object visitCastExpression(final CastExpressionTree castExpressionTree,
                                      final Scope scope) {
        final var module = scope.findModuleElement();
        final var joinClass = loader.loadClass(module, "io.github.potjerodekool.nabu.lang.jpa.support.Join");

        final var joinTypeMirror = joinClass.asType();

        final var targetType = castExpressionTree.getTargetType().getType();

        final var isJointType = types.isAssignable(
                targetType,
                joinTypeMirror
        );

        if (isJointType) {
            final var declaredType = (DeclaredType) targetType;
            final var joinName = declaredType.asElement().getSimpleName();

            JoinConverter converter;

            switch (joinName) {
                case "InnerJoin" -> converter = new JoinConverter(compilerContext, "INNER");
                case "LeftJoin" -> converter = new JoinConverter(compilerContext, "LEFT");
                case "rightJoin" -> converter = new JoinConverter(compilerContext, "RIGHT");
                case null, default -> {
                    return super.visitCastExpression(castExpressionTree, scope);
                }
            }

            final var newCastExpression = (CastExpressionTree) super.visitCastExpression(castExpressionTree, scope);

            return newCastExpression.getExpression().accept(
                    converter,
                    scope
            );
        } else {
            return super.visitCastExpression(castExpressionTree, scope);
        }
    }

    @Override
    public Object visitUnknown(final Tree tree,
                               final Scope Param) {
        return tree;
    }

    @Override
    public Object defaultAnswer(final Tree tree, final Scope param) {
        return tree;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final Scope scope) {
        var type = identifier.getType();

        if (type == null) {
            type = resolveType(identifier.getName(), scope);
        }

        if (type != null && !type.isError()) {
            identifier.setType(type);
            identifier.setSymbol(null);
        } else {
            final var symbol = scope.resolve(identifier.getName());

            if (symbol != null) {
                identifier.setType(null);
                identifier.setSymbol(symbol);
            } else if (identifier.getSymbol() == null) {
                identifier.setSymbol(
                        compilerContext.getElementBuilders()
                                .createErrorSymbol(identifier.getName())
                );
            }
        }

        return defaultAnswer(identifier, scope);
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

}

package io.github.potjerodekool.nabu.plugin.jpa.transform;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.ElementFilter;
import io.github.potjerodekool.nabu.compiler.resolve.scope.*;
import io.github.potjerodekool.nabu.compiler.transform.CodeTransformer;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.potjerodekool.nabu.compiler.resolve.TreeUtils.getSymbol;
import static io.github.potjerodekool.nabu.compiler.resolve.TreeUtils.resolveType;

public class JpaTransformer extends AbstractJpaTransformer implements CodeTransformer {

    private static final String CRITERIA_BUILDER_CLASS = "jakarta.persistence.criteria.CriteriaBuilder";

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


    private TypeMirror joinTypeMirror;

    public JpaTransformer(final CompilerContext compilerContext) {
        super(compilerContext);
        this.compilerContext = compilerContext;
        loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getClassElementLoader().getTypes();
    }

    @Override
    public void tranform(final CompilationUnit compilationUnit) {
        compilationUnit.accept(this, null);
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit, final Scope scope) {
        final var globalScope = new GlobalScope(compilationUnit, compilerContext);
        return super.visitCompilationUnit(compilationUnit, globalScope);
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration, final Scope scope) {
        final var clazz = classDeclaration.getClassSymbol();
        final var classScope = new SymbolScope((DeclaredType) clazz.asType(), scope);
        classDeclaration.getEnclosedElements()
                .forEach(enclosingElement -> enclosingElement.accept(this, classScope));
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
        final var newBody = (Statement) lambdaExpression.getBody().accept(this, scope);
        lambdaExpression.body(newBody);
        return lambdaExpression;
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                             final Scope scope) {
        var target = fieldAccessExpression.getTarget();
        final var newTarget = (ExpressionTree) target.accept(this, scope);

        if (newTarget != target) {
            fieldAccessExpression.target(newTarget);
            target = newTarget;
        }

        final var newField = fieldAccessExpression.getField().accept(this, scope);

        final var symbol = getSymbol(target);

        if (symbol instanceof VariableElement variableElement) {
            final var variableType = variableElement.asType();

            final var pathType = resolvePathType();
            final var isPath = types.isSubType(variableType, pathType);

            if (isPath) {
                final var field = (IdentifierTree) newField;
                final var literal = TreeMaker.literalExpressionTree(field.getName(), -1, -1);
                final var stringType = loader.loadClass(ClassNames.STRING_CLASS_NAME).asType();
                literal.setType(stringType);

                final var targetType = resolveVariableType(variableElement);
                final var fieldType = resolveFieldType(targetType, field.getName());

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
                        target,
                        IdentifierTree.create("get"),
                        typeArguments,
                        List.of(literal),
                        -1,
                        -1
                );

                final var methodType = compilerContext.getMethodResolver().resolveMethod(methodInvocation, null);
                methodInvocation.setMethodType(methodType);
                return methodInvocation;
            }
        }

        return fieldAccessExpression;
    }

    private DeclaredType resolveVariableType(final VariableElement variableElement) {
        final var declaredType = (DeclaredType) variableElement.asType();
        return (DeclaredType) declaredType.getTypeArguments().getLast();
    }

    private TypeMirror resolveFieldType(final DeclaredType declaredType,
                                        final String fieldName) {
        TypeMirror fieldType = null;

        final var clazz = (TypeElement) declaredType.asElement();

        final var fieldTypeOptional = ElementFilter.fields(clazz).stream()
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

            final var pathType = resolvePathType();

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
                builderIdentifier,
                methodName,
                binaryExpression.getLeft(),
                binaryExpression.getRight()
        );
    }

    private Optional<VariableElement> findBuilderVariable(final Scope scope) {
        final var criteriaBuilderType = loader.loadClass(CRITERIA_BUILDER_CLASS).asType();
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
    public Object visiExpressionStatement(final ExpressionStatement expressionStatement, final Scope scope) {
        final var newExpression = (ExpressionTree) expressionStatement.getExpression().accept(this, scope);

        if (newExpression != expressionStatement.getExpression()) {
            return expressionStatement.builder()
                    .expression(newExpression)
                    .build();
        }

        return expressionStatement;
    }

    @Override
    public Object visitBlockStatement(final BlockStatement blockStatement, final Scope scope) {
        final var newStatements = blockStatement.getStatements().stream()
                .map(statement -> {
                    final var result = statement.accept(this, scope);

                    if (!(result instanceof Statement)) {
                        throw new IllegalArgumentException();
                    }

                    return (Statement) result;
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
    public Object visitVariableDeclaratorStatement(final VariableDeclarator variableDeclaratorStatement, final Scope scope) {
        final var newType = (ExpressionTree) variableDeclaratorStatement.getType().accept(this, scope);
        final var newIdentifier = (IdentifierTree) variableDeclaratorStatement.getName().accept(this, scope);
        final var newValue = accept(variableDeclaratorStatement.getValue(), scope);

        final var newVariableDeclaratorStatement = variableDeclaratorStatement.builder()
                .type(newType)
                .name(newIdentifier)
                .value(newValue)
                .build();

        final TypeMirror type;

        if (newVariableDeclaratorStatement.getValue() != null
                && variableDeclaratorStatement.getType() instanceof VariableTypeTree) {
            type = resolveType(newVariableDeclaratorStatement.getValue());
        } else {
            type = resolveType(newType);
        }

        final var varElement = new VariableBuilder()
                .kind(ElementKind.LOCAL_VARIABLE)
                .name(newIdentifier.getName())
                .type(type)
                .build();


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
                target,
                "not",
                newExpression
        );
    }

    @Override
    public Object visitLiteralExpression(final LiteralExpressionTree literalExpression, final Scope param) {
        super.visitLiteralExpression(literalExpression, param);

        final TypeMirror type = switch (literalExpression.getLiteralKind()) {
            case INTEGER -> types.getPrimitiveType(TypeKind.INT);
            case LONG -> types.getPrimitiveType(TypeKind.LONG);
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case STRING -> loader.loadClass(ClassNames.STRING_CLASS_NAME).asType();
            case NULL -> types.getNullType();
            case CLASS -> loader.loadClass(ClassNames.CLASS_CLASS_NAME).asType();
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
    public Object visitReturnStatement(final ReturnStatement returnStatement,
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

    private void initJoinTypes() {
        if (joinTypeMirror == null) {
            this.joinTypeMirror = loader.loadClass("io.github.potjerodekool.nabu.lang.jpa.support.Join").asType();
        }
    }

    @Override
    public Object visitCastExpression(final CastExpressionTree castExpressionTree,
                                      final Scope scope) {
        initJoinTypes();
        final var targetType = castExpressionTree.getTargetType().getType();

        final var isJointType = types.isAssignable(
                targetType,
                this.joinTypeMirror
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

            return castExpressionTree.getExpression().accept(
                    converter,
                    scope
            );
        } else {
            return super.visitCastExpression(castExpressionTree, scope);
        }
    }
}

package io.github.potjerodekool.nabu.compiler.transform;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.Types;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeCreator;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.type.ClassType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableVariableType;

import java.util.Map;
import java.util.Optional;

public class JpaTransformer extends AbstractJpaTransformer {

    private static final String ROOT_CLASS = "jakarta.persistence.criteria.Root";
    private static final String PATH_CLASS = "jakarta.persistence.criteria.Path";
    private static final String CRITERIA_BUILDER_CLASS = "jakarta.persistence.criteria.CriteriaBuilder";

    private final CompilerContext compilerContext;
    private final ClassElementLoader loader;
    private final Types types;

    private final Map<Operator, String> operatorMapping = Map.of(
            Operator.EQ, "equal",
            Operator.NOT_EQ, "notEqual",
            Operator.LT, "lessThan",
            Operator.GT, "greaterThan",
            Operator.LE, "lessThanOrEqualTo",
            Operator.GE, "greaterThanOrEqualTo"
    );

    public JpaTransformer(final CompilerContext compilerContext) {
        super(compilerContext);
        this.compilerContext = compilerContext;
        loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getClassElementLoader().getTypes();
    }

    @Override
    public Object visitLambdaExpression(final CLambdaExpression lambdaExpression, final Scope scope) {
        final var classType = (ClassType) lambdaExpression.getType();
        final var classElement = (ClassSymbol) classType.asElement();
        final var functionalMethod = classElement.findFunctionalMethod();
        final var methodType = functionalMethod.getMethodType();
        final var argumentTypes = methodType.getArgumentTypes();
        final var variables = lambdaExpression.getVariables();

        for (int argIndex = 0; argIndex < argumentTypes.size(); argIndex++) {
            final var argumentType = (ClassType) argumentTypes.get(argIndex);
            final var argumentClass = (ClassSymbol) argumentType.asElement();
            var variable = argIndex < variables.size()
                ? variables.get(argIndex)
                    : null;

            if (variable == null) {
                final var name = generateVariableName(scope);
                final var newVarType = copy(argumentType);
                final var typeTree = TreeCreator.createTypeTree(newVarType);
                final var parameter = new VariableElement(
                        ElementKind.PARAMETER,
                        name,
                        null
                );
                parameter.setVariableType(typeTree.getType());

                variable = new CVariable();
                variable.type(typeTree);
                variable.simpleName(name);
                variable.setVarSymbol(parameter);
                variables.add(argIndex, variable);

                scope.define(parameter);
            } else if (ROOT_CLASS.equals(argumentClass.getQualifiedName())) {
                final var variableType = (ClassType) variable.getType().getType();
                final var variableTypeElement = (ClassSymbol) variableType.asElement();
                final var typeArg = types.getDeclaredType(variableTypeElement);
                final var newType = types.getDeclaredType(argumentClass, typeArg);
                variable.type(TreeCreator.createTypeTree(newType));

                final var variableName = variable.getSimpleName();
                final var varSymbol = (VariableElement) scope.resolve(variableName);
                varSymbol.setVariableType(newType);
            }
        }

        lambdaExpression.getVariables().forEach(variable -> variable.accept(this, scope));
        final var newBody = (Statement) lambdaExpression.getBody().accept(this, scope);
        lambdaExpression.body(newBody);
        return lambdaExpression;
    }

    @Override
    public Object visitFieldAccessExpression(final CFieldAccessExpression fieldAccessExpression,
                                             final Scope scope) {
        var target = fieldAccessExpression.getTarget();
        final var newTarget = (CExpression) target.accept(this, scope);

        if (newTarget != target) {
            fieldAccessExpression.setTarget(newTarget);
            target = newTarget;
        }

        final var newField = fieldAccessExpression.getField().accept(this, scope);

        final var symbol = getSymbol(target);

        if (symbol instanceof VariableElement variableElement) {
            final var variableType = variableElement.getVariableType();

            final var pathType = resolvePathType();
            final var isPath = types.isSubType(variableType, pathType);

            if (isPath) {
                final var field = (CIdent) newField;
                final var literal = new LiteralExpression(field.getName());
                final var stringType = loader.resolveType(Constants.STRING);
                literal.setType(stringType);

                final var methodInvocation = new MethodInvocation();
                methodInvocation.target(target);
                methodInvocation.name(new CIdent("get"));
                methodInvocation.argument(literal);

                final var methodType = compilerContext.getMethodResolver().resolveMethod(methodInvocation);
                methodInvocation.setMethodType(methodType);
                return methodInvocation;
            }
        }

        return fieldAccessExpression;
    }

    private TypeMirror resolvePathType() {
        return loader.resolveType(PATH_CLASS);
    }

    private Element getSymbol(final CExpression expression) {
        if (expression instanceof CFieldAccessExpression fieldAccessExpression) {
            return getSymbol(fieldAccessExpression.getTarget());
        } else if (expression instanceof MethodInvocation methodInvocation) {
            return getSymbol(methodInvocation.getTarget());
        }

        return expression.getSymbol();
    }

    private String generateVariableName(final Scope scope) {
        int counter = 0;
        String name;
        Element symbol;

        do {
            name = "$p" + counter;
            symbol = scope.resolve("$p" + counter);
            counter++;
        } while (symbol != null);
        return name;
    }

    private TypeMirror copy(final TypeMirror typeMirror) {
        if (typeMirror instanceof ClassType classType) {
            final var clazz = (ClassSymbol) classType.asElement();
            final TypeMirror[] parameterTypes;

            if (classType.getParameterTypes() != null) {
                parameterTypes = classType.getParameterTypes().stream()
                        .map(this::copy)
                        .toArray(TypeMirror[]::new);
            } else {
                parameterTypes = new TypeMirror[0];
            }

            return types.getDeclaredType(clazz, parameterTypes);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpression binaryExpression, final Scope scope) {
        final var newLeft = (CExpression) binaryExpression.getLeft().accept(this, scope);
        final var newRight = (CExpression) binaryExpression.getRight().accept(this, scope);
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

    private CExpression transformAndOr(final BinaryExpression binaryExpression,
                                       final Scope scope) {
        final var builderVariableOptional = findBuilderVariable(scope);

        if (builderVariableOptional.isEmpty()) {
            return binaryExpression;
        }

        final var builderVariable = builderVariableOptional.get();

        final var operator = binaryExpression.getOperator();

        if (operator == Operator.AND) {
            final var target = new CIdent(builderVariable.getSimpleName());
            target.setSymbol(builderVariable);

            return createBuilderCall(
                    target,
                    "and",
                    binaryExpression.getLeft(),
                    binaryExpression.getRight()
            );
        } else if (operator == Operator.OR) {
            final var target = new CIdent(builderVariable.getSimpleName());
            target.setSymbol(builderVariable);

            return createBuilderCall(
                    target,
                    "or",
                    binaryExpression.getLeft(),
                    binaryExpression.getRight()
            );
        } else {
            return binaryExpression;
        }
    }

    private CExpression transformBinaryExpression(final BinaryExpression binaryExpression, final Scope scope) {
        final var methodName = resolveMethodName(binaryExpression.getOperator());
        final var builderVariableOptional = findBuilderVariable(scope);

        if (builderVariableOptional.isEmpty()) {
            return binaryExpression;
        }

        final var builderVariable = builderVariableOptional.get();
        String builderName = builderVariable.getSimpleName();

        final var cIdent = new CIdent(builderName);
        cIdent.setSymbol(builderVariable);

        return createBuilderCall(
                cIdent,
                methodName,
                binaryExpression.getLeft(),
                binaryExpression.getRight()
        );
    }

    private Optional<VariableElement> findBuilderVariable(final Scope scope) {
        final var criteriaBuilderType = loader.resolveType(CRITERIA_BUILDER_CLASS);
        return findBuilderVariable(scope, criteriaBuilderType);
    }

    private Optional<VariableElement> findBuilderVariable(final Scope scope,
                                                          final TypeMirror criteriaBuilderType) {
        final var variableOptional = scope.locals().stream()
                .map(scope::resolve)
                .filter(symbol -> symbol instanceof VariableElement)
                .map(variable -> (VariableElement) variable)
                .filter(variableElement -> {
                    final var varType = variableElement.getVariableType();
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

    private String resolveMethodName(final Operator operator) {
        return operatorMapping.get(operator);
    }

    @Override
    public Object visitStatementExpression(final StatementExpression statementExpression, final Scope scope) {
        final var newExpression = (CExpression) statementExpression.getExpression().accept(this, scope);

        if (newExpression != statementExpression.getExpression()) {
            return statementExpression.builder()
                    .expression(newExpression)
                    .build();
        }

        return statementExpression;
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

    @Override
    public Object visitVariableDeclaratorStatement(final CVariableDeclaratorStatement variableDeclaratorStatement, final Scope scope) {


        final var newType = (CExpression) variableDeclaratorStatement.getType().accept(this, scope);
        final var newIdent = (CIdent) variableDeclaratorStatement.getIdent().accept(this, scope);
        final var newValue = (Tree) variableDeclaratorStatement.getValue().accept(this, scope);

        final var newVariableDeclaratorStatement = variableDeclaratorStatement.builder()
                .type(newType)
                .ident(newIdent)
                .value(newValue)
                .build();

        final var varElement = new VariableElement(ElementKind.VARIABLE, newIdent.getName(), null);
        final var type = resolveType(newType);
        varElement.setVariableType(type);

        if (type instanceof MutableVariableType variableType) {
            final var interferedType = resolveType(newVariableDeclaratorStatement.getValue());
            variableType.setInterferedType(interferedType);
        }

        scope.define(varElement);

        return newVariableDeclaratorStatement;
    }

    @Override
    public Object visitUnaryExpression(final UnaryExpression unaryExpression, final Scope scope) {
        final var newExpression = (CExpression) unaryExpression.getExpression().accept(this, scope);

        if (unaryExpression.getOperator() != Operator.BANG) {
            throw new TodoException();
        }

        final var builderVariableOptional = findBuilderVariable(scope);

        if (builderVariableOptional.isEmpty()) {
            return unaryExpression;
        }

        final var builderVariable = builderVariableOptional.get();

        final var target = new CIdent(builderVariable.getSimpleName());
        target.setSymbol(builderVariable);

        return createBuilderCall(
                target,
                "not",
                newExpression
        );
    }

    @Override
    public Object visitLiteralExpression(final LiteralExpression literalExpression, final Scope param) {
        super.visitLiteralExpression(literalExpression, param);

        final TypeMirror type = switch (literalExpression.getLiteralKind()) {
            case NULL -> types.getNullType();
            case CLASS -> loader.resolveType(Constants.CLAZZ);
            case STRING -> loader.resolveType(Constants.STRING);
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
        };

        literalExpression.setType(type);

        return literalExpression;
    }

    @Override
    public Object visitReturnStatement(final ReturnStatement returnStatement, final Scope scope) {
        final var newExpression = (CExpression) returnStatement.getExpression().accept(this, scope);

        if (newExpression != returnStatement.getExpression()) {
            return returnStatement.builder()
                    .expression(newExpression)
                    .build();
        }

        return returnStatement;
    }

    @Override
    public Object visitAsExpression(final AsExpression asExpression,
                                    final Scope scope) {
        final var innerJoinType =
                loader.resolveType("io.github.potjerodekool.nabu.lang.jpa.InnerJoin");

        final var targetType = asExpression.getTargetType().getType();

        final var isInnerJoin = types.isAssignable(
                targetType,
                innerJoinType
        );

        if (isInnerJoin) {
            final var joinTransformer = new InnerJoinTransformer(
                    compilerContext
            );

            return asExpression.getExpression().accept(
                    joinTransformer,
                    scope
            );
        } else {
            return super.visitAsExpression(asExpression, scope);
        }
    }
}

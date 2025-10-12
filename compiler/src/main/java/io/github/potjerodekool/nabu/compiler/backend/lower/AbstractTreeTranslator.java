package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.statement.*;

public abstract class AbstractTreeTranslator<P> implements TreeVisitor<Tree, P> {

    @Override
    public Tree visitUnknown(final Tree tree, final P param) {
        return null;
    }

    @Override
    public Tree visitCompilationUnit(final CompilationUnit compilationUnit, final P param) {
        compilationUnit.getClasses()
                .forEach(classDeclaration -> classDeclaration.accept(this, param));
        return compilationUnit;
    }

    @Override
    public Tree visitFunction(final Function function, final P param) {
        final var body = function.getBody();
        final BlockStatementTree newBody = accept(body, param);

        if (newBody != body) {
            return function.builder()
                    .body(newBody)
                    .build();
        }

        return function;
    }

    @Override
    public Tree visitBlockStatement(final BlockStatementTree blockStatement, final P param) {
        final var newStatements = blockStatement.getStatements().stream()
                .map(statement -> (StatementTree) statement.accept(this, param))
                .toList();
        return blockStatement.builder()
                .statements(newStatements)
                .build();
    }

    @Override
    public Tree visitReturnStatement(final ReturnStatementTree returnStatement, final P param) {
        final var expression = returnStatement.getExpression();

        if (expression == null) {
            return returnStatement;
        }

        final var newExpression = (ExpressionTree) expression.accept(this, param);

        if (newExpression != expression) {
            return returnStatement.builder()
                    .expression(newExpression)
                    .build();
        } else {
            return returnStatement;
        }
    }

    @Override
    public Tree visitIdentifier(final IdentifierTree identifier, final P param) {
        return identifier;
    }

    @Override
    public Tree visitLambdaExpression(final LambdaExpressionTree lambdaExpression, final P param) {
        final var variables = lambdaExpression.getVariables().stream()
                .map(it -> (VariableDeclaratorTree) it.accept(this, param))
                .toList();

        final var body = (StatementTree) lambdaExpression.getBody().accept(this, param);

        return lambdaExpression.builder()
                .variables(variables)
                .body(body)
                .build();
    }

    @Override
    public Tree visitBinaryExpression(final BinaryExpressionTree binaryExpression,
                                      final P param) {
        return binaryExpression;
    }

    @Override
    public Tree visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression, final P param) {
        final var selected = (ExpressionTree) fieldAccessExpression.getSelected().accept(this, param);
        final var field = (ExpressionTree) fieldAccessExpression.getField().accept(this, param);
        return fieldAccessExpression.builder()
                .selected(selected)
                .field(field)
                .build();
    }

    @Override
    public Tree visitClass(final ClassDeclaration classDeclaration, final P param) {
        final var enclosedElements = classDeclaration.getEnclosedElements();

        for (int i = 0; i < enclosedElements.size(); i++) {
            var enclosedElement = enclosedElements.get(i);
            enclosedElement = enclosedElement.accept(this, param);
            enclosedElements.set(i, enclosedElement);
        }
        return classDeclaration;
    }

    @Override
    public Tree visitMethodInvocation(final MethodInvocationTree methodInvocation, final P param) {
        final var selector = (ExpressionTree) methodInvocation.getMethodSelector().accept(this, param);
        final var typeArguments = methodInvocation.getTypeArguments().stream()
                .map(it -> (IdentifierTree) it.accept(this, param))
                .toList();
        final var arguments = methodInvocation.getArguments().stream()
                .map(it -> (ExpressionTree) it.accept(this, param))
                .toList();

        return methodInvocation.builder()
                .methodSelector(selector)
                .typeArguments(typeArguments)
                .arguments(arguments)
                .build();
    }

    @Override
    public Tree visitLiteralExpression(final LiteralExpressionTree literalExpression, final P param) {
        return literalExpression;
    }

    @Override
    public Tree visitExpressionStatement(final ExpressionStatementTree expressionStatement, final P param) {
        final var expression = expressionStatement.getExpression();
        final var newExpression = (ExpressionTree) expression.accept(this, param);

        if (newExpression != expression) {
            return expressionStatement.builder()
                    .expression(newExpression)
                    .build();
        } else {
            return expressionStatement;
        }
    }

    @Override
    public Tree visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final P param) {
        return variableDeclaratorStatement;
    }

    @Override
    public Tree visitPackageDeclaration(final PackageDeclaration packageDeclaration, final P param) {
        return packageDeclaration;
    }

    @Override
    public Tree visitImportItem(final ImportItem importItem, final P param) {
        return importItem;
    }

    @Override
    public Tree visitPrimitiveType(final PrimitiveTypeTree primitiveType, final P param) {
        return primitiveType;
    }

    @Override
    public Tree visitUnaryExpression(final UnaryExpressionTree unaryExpression, final P param) {
        return unaryExpression;
    }

    @Override
    public Tree visitTypeIdentifier(final TypeApplyTree typeIdentifier, final P param) {
        return typeIdentifier;
    }

    @Override
    public Tree visitAnnotatedType(final AnnotatedTypeTree annotatedType, final P param) {
        return annotatedType;
    }

    @Override
    public Tree visitTypeNameExpression(final TypeNameExpressionTree typeNameExpression, final P param) {
        return typeNameExpression;
    }

    @Override
    public Tree visitVariableType(final VariableTypeTree variableType, final P param) {
        return variableType;
    }

    @Override
    public Tree visitCastExpression(final CastExpressionTree castExpressionTree, final P param) {
        return castExpressionTree;
    }

    @Override
    public Tree visitWildCardExpression(final WildcardExpressionTree wildCardExpression, final P param) {
        return wildCardExpression;
    }

    @Override
    public Tree visitIfStatement(final IfStatementTree ifStatementTree, final P param) {
        final var expression = (ExpressionTree) ifStatementTree.getExpression().accept(this, param);
        final var thenStatement = (StatementTree) ifStatementTree.getThenStatement().accept(this, param);
        final StatementTree elseStatement = accept(ifStatementTree.getElseStatement(), param);

        return ifStatementTree.builder()
                .expression(expression)
                .thenStatement(thenStatement)
                .elseStatement(elseStatement)
                .build();
    }

    @Override
    public Tree visitEmptyStatement(final EmptyStatementTree emptyStatementTree, final P param) {
        return emptyStatementTree;
    }

    protected <T extends Tree> T accept(final T tree,
                                        final P param) {
        return tree != null
                ? (T) tree.accept(this, param)
                : null;
    }

    @Override
    public Tree visitForStatement(final ForStatementTree forStatement, final P param) {
        final var newInit = forStatement.getForInit().stream()
                .map(it -> (StatementTree) it.accept(this, param))
                .toList();

        final ExpressionTree newExpression = accept(forStatement.getExpression(), param);
        final var newUpdate = forStatement.getForUpdate().stream()
                .map(it -> (StatementTree) it.accept(this, param))
                .toList();

        final var newStatement = (StatementTree) forStatement.getStatement().accept(this, param);

        return forStatement.builder()
                .forInit(newInit)
                .expression(newExpression)
                .forUpdate(newUpdate)
                .statement(newStatement)
                .build();
    }

    @Override
    public Tree visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement, final P param) {
        final var newExpression = (ExpressionTree) enhancedForStatement.getExpression().accept(this, param);
        final var newLocalVariable = (VariableDeclaratorTree) enhancedForStatement.getLocalVariable().accept(this, param);
        final var newStatement = (StatementTree) enhancedForStatement.getStatement().accept(this, param);

        return enhancedForStatement.builder()
                .localVariable(newLocalVariable)
                .expression(newExpression)
                .statement(newStatement)
                .build();
    }

    @Override
    public Tree visitAnnotation(final AnnotationTree annotationTree, final P param) {
        return annotationTree;
    }

    @Override
    public Tree visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression, final P param) {
        return visitUnknown(instanceOfExpression, param);
    }

    @Override
    public Tree visitNewClass(final NewClassExpression newClassExpression, final P param) {
        return visitUnknown(newClassExpression, param);
    }

    @Override
    public Tree visitWhileStatement(final WhileStatementTree whileStatement, final P param) {
        final var condition = (ExpressionTree) whileStatement.getCondition().accept(this, param);
        final var body = (StatementTree) whileStatement.getBody().accept(this, param);
        return whileStatement.builder()
                .condition(condition)
                .body(body)
                .build();
    }

    @Override
    public Tree visitDoWhileStatement(final DoWhileStatementTree doWhileStatement, final P param) {
        final var body = (StatementTree) doWhileStatement.getBody().accept(this, param);
        final var condition = (ExpressionTree) doWhileStatement.getCondition().accept(this, param);
        return doWhileStatement.builder()
                .body(body)
                .condition(condition)
                .build();
    }

    @Override
    public Tree visitTypeParameter(final TypeParameterTree typeParameterTree, final P param) {
        return visitUnknown(typeParameterTree, param);
    }

}

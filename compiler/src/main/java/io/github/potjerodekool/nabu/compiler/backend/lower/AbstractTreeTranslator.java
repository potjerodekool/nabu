package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;

public abstract class AbstractTreeTranslator implements TreeVisitor<Tree, Scope> {

    @Override
    public Tree visitUnknown(final Tree tree, final Scope Param) {
        return null;
    }

    @Override
    public Tree visitCompilationUnit(final CompilationUnit compilationUnit, final Scope scope) {
        compilationUnit.getClasses()
                .forEach(classDeclaration -> classDeclaration.accept(this, scope));
        return compilationUnit;
    }

    @Override
    public Tree visitFunction(final Function function, final Scope scope) {
        final var body = function.getBody();
        final var newBody = (BlockStatement) body.accept(this, scope);

        if (newBody != body) {
            return function.builder()
                    .body(newBody)
                    .build();
        }

        return function;
    }

    @Override
    public Tree visitBlockStatement(final BlockStatement blockStatement, final Scope scope) {
        final var newStatements = blockStatement.getStatements().stream()
                .map(statement -> (Statement) statement.accept(this, scope))
                .toList();
        return blockStatement.builder()
                .statements(newStatements)
                .build();
    }

    @Override
    public Tree visitReturnStatement(final ReturnStatement returnStatement, final Scope scope) {
        final var expression = returnStatement.getExpression();

        if (expression == null) {
            return returnStatement;
        }

        final var newExpression = (ExpressionTree) expression.accept(this, scope);

        if (newExpression != expression) {
            return returnStatement.builder()
                    .expression(newExpression)
                    .build();
        } else {
            return returnStatement;
        }
    }

    @Override
    public Tree visitIdentifier(final IdentifierTree identifier, final Scope scope) {
        return identifier;
    }

    @Override
    public Tree visitLambdaExpression(final LambdaExpressionTree lambdaExpression, final Scope scope) {
        final var variables = lambdaExpression.getVariables().stream()
                .map(it -> (VariableDeclarator) it.accept(this, scope))
                .toList();

        final var body = (Statement) lambdaExpression.getBody().accept(this, scope);

        return lambdaExpression.builder()
                .variables(variables)
                .body(body)
                .build();
    }

    @Override
    public Tree visitBinaryExpression(final BinaryExpressionTree binaryExpression,
                                      final Scope scope) {
        return binaryExpression;
    }

    @Override
    public Tree visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression, final Scope scope) {
        return fieldAccessExpression;
    }

    @Override
    public Tree visitClass(final ClassDeclaration classDeclaration, final Scope scope) {
        final var enclosedElements = classDeclaration.getEnclosedElements();

        for (int i = 0; i < enclosedElements.size(); i++) {
            var enclosedElement = enclosedElements.get(i);
            enclosedElement = enclosedElement.accept(this, scope);
            enclosedElements.set(i, enclosedElement);
        }
        return classDeclaration;
    }

    @Override
    public Tree visitMethodInvocation(final MethodInvocationTree methodInvocation, final Scope scope) {
        return methodInvocation;
    }

    @Override
    public Tree visitLiteralExpression(final LiteralExpressionTree literalExpression, final Scope scope) {
        return literalExpression;
    }

    @Override
    public Tree visiExpressionStatement(final ExpressionStatement expressionStatement, final Scope scope) {
        final var expression = expressionStatement.getExpression();
        final var newExpression = (ExpressionTree) expression.accept(this, scope);

        if (newExpression != expression) {
            return expressionStatement.builder()
                    .expression(newExpression)
                    .build();
        } else {
            return expressionStatement;
        }
    }

    @Override
    public Tree visitVariableDeclaratorStatement(final VariableDeclarator variableDeclaratorStatement, final Scope scope) {
        return variableDeclaratorStatement;
    }

    @Override
    public Tree visitPackageDeclaration(final PackageDeclaration packageDeclaration, final Scope scope) {
        return packageDeclaration;
    }

    @Override
    public Tree visitImportItem(final ImportItem importItem, final Scope scope) {
        return importItem;
    }

    @Override
    public Tree visitPrimitiveType(final PrimitiveTypeTree primitiveType, final Scope scope) {
        return primitiveType;
    }

    @Override
    public Tree visitUnaryExpression(final UnaryExpressionTree unaryExpression, final Scope scope) {
        return unaryExpression;
    }

    @Override
    public Tree visitTypeIdentifier(final TypeApplyTree typeIdentifier, final Scope scope) {
        return typeIdentifier;
    }

    @Override
    public Tree visitAnnotatedType(final AnnotatedTypeTree annotatedType, final Scope scope) {
        return annotatedType;
    }

    @Override
    public Tree visitTypeNameExpression(final TypeNameExpressionTree typeNameExpression, final Scope scope) {
        return typeNameExpression;
    }

    @Override
    public Tree visitVariableType(final VariableTypeTree variableType, final Scope scope) {
        return variableType;
    }

    @Override
    public Tree visitCastExpression(final CastExpressionTree castExpressionTree, final Scope scope) {
        return castExpressionTree;
    }

    @Override
    public Tree visitWildCardExpression(final WildcardExpressionTree wildCardExpression, final Scope scope) {
        return wildCardExpression;
    }

    @Override
    public Tree visitIfStatement(final IfStatementTree ifStatementTree, final Scope scope) {
        final var expression = (ExpressionTree) ifStatementTree.getExpression().accept(this, scope);
        final var thenStatement = (Statement) ifStatementTree.getThenStatement().accept(this, scope);
        final var elseStatement = accept(ifStatementTree.getElseStatement(), scope, Statement.class);

        return ifStatementTree.builder()
                .expression(expression)
                .thenStatement(thenStatement)
                .elseStatement(elseStatement)
                .build();
    }

    @Override
    public Tree visitEmptyStatement(final EmptyStatementTree emptyStatementTree, final Scope scope) {
        return emptyStatementTree;
    }

    protected <T extends Tree> T accept(final T tree,
                                        final Scope scope,
                                        final Class<T> returnType) {
        return tree != null
                ? returnType.cast(tree.accept(this, scope))
                : null;
    }

    @Override
    public Tree visitForStatement(final ForStatement forStatement, final Scope scope) {
        final var newInit = accept(forStatement.getForInit(), scope, Statement.class);
        final var newExpression = accept(forStatement.getExpression(), scope, ExpressionTree.class);
        final var newUpdate = accept(forStatement.getForUpdate(), scope, ExpressionTree.class);
        final var newStatement = (Statement) forStatement.getStatement().accept(this, scope);

        return forStatement.builder()
                .forInit(newInit)
                .expression(newExpression)
                .forUpdate(newUpdate)
                .statement(newStatement)
                .build();
    }

    @Override
    public Tree visitEnhancedForStatement(final EnhancedForStatement enhancedForStatement, final Scope scope) {
        final var newExpression = (ExpressionTree) enhancedForStatement.getExpression().accept(this, scope);
        final var newLocalVariable = (VariableDeclarator) enhancedForStatement.getLocalVariable().accept(this, scope);
        final var newStatement = (Statement) enhancedForStatement.getStatement().accept(this, scope);

        return enhancedForStatement.builder()
                .localVariable(newLocalVariable)
                .expression(newExpression)
                .statement(newStatement)
                .build();
    }

    @Override
    public Tree visitAnnotation(final AnnotationTree annotationTree, final Scope scope) {
        return annotationTree;
    }

    @Override
    public Tree visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression, final Scope scope) {
        return null;
    }

    @Override
    public Tree visitNewClass(final NewClassExpression newClassExpression, final Scope scope) {
        return null;
    }

    @Override
    public Tree visitWhileStatement(final WhileStatement whileStatement, final Scope scope) {
        final var condition = (ExpressionTree) whileStatement.getCondition().accept(this, scope);
        final var body = (Statement) whileStatement.getBody().accept(this, scope);
        return whileStatement.builder()
                .condition(condition)
                .body(body)
                .build();
    }

    @Override
    public Tree visitDoWhileStatement(final DoWhileStatement doWhileStatement, final Scope scope) {
        final var body = (Statement) doWhileStatement.getBody().accept(this, scope);
        final var condition = (ExpressionTree) doWhileStatement.getCondition().accept(this, scope);
        return doWhileStatement.builder()
                .body(body)
                .condition(condition)
                .build();
    }

    @Override
    public Tree visitTypeParameter(final TypeParameterTree typeParameterTree, final Scope param) {
        return null;
    }
}

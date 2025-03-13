package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;

public abstract class AbstractTreeVisitor<R, P> implements TreeVisitor<R, P> {

    @Override
    public R visitUnknown(final Tree tree, final P Param) {
        return null;
    }

    @Override
    public R visitCompilationUnit(final CompilationUnit compilationUnit,
                                  final P param) {
        if (compilationUnit.getPackageDeclaration() != null) {
            compilationUnit.getPackageDeclaration().accept(this, param);
        }

        compilationUnit.getImportItems().forEach(importItem ->
                importItem.accept(this, param)
        );

        compilationUnit.getClasses().forEach(e -> e.accept(this, param));
        return null;
    }

    @Override
    public R visitImportItem(final ImportItem importItem, final P param) {
        return null;
    }

    @Override
    public R visitFunction(final Function function, final P param) {
        function.getParameters().forEach(fp -> fp.accept(this, param));
        final var returnType = function.getReturnType();

        if (returnType != null) {
            returnType.accept(this, param);
        }

        final var body = function.getBody();

        if (body != null) {
            body.accept(this, param);
        }

        return null;
    }

    @Override
    public R visitBlockStatement(final BlockStatement blockStatement, final P param) {
        blockStatement.getStatements().forEach(s -> s.accept(this, param));
        return null;
    }

    @Override
    public R visitReturnStatement(final ReturnStatement returnStatement, final P param) {
        final var expression = returnStatement.getExpression();

        if (expression != null) {
            return returnStatement.getExpression().accept(this, param);
        } else {
            return null;
        }
    }


    @Override
    public R visitLambdaExpression(final LambdaExpressionTree lambdaExpression, final P param) {
        lambdaExpression.getVariables().forEach(variable -> variable.accept(this, param));
        lambdaExpression.getBody().accept(this, param);
        return null;
    }

    @Override
    public R visitBinaryExpression(final BinaryExpressionTree binaryExpression, final P param) {
        binaryExpression.getLeft().accept(this, param);
        binaryExpression.getRight().accept(this, param);
        return null;
    }

    @Override
    public R visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression, final P param) {
        fieldAccessExpression.getTarget().accept(this, param);
        fieldAccessExpression.getField().accept(this, param);
        return null;
    }

    @Override
    public R visitClass(final ClassDeclaration classDeclaration, final P param) {
        classDeclaration.getEnclosedElements().forEach(e -> e.accept(this, param));
        return null;
    }

    @Override
    public R visitMethodInvocation(final MethodInvocationTree methodInvocation, final P param) {
        final var target = methodInvocation.getTarget();
        if (target != null) {
            target.accept(this, param);
        }
        methodInvocation.getName().accept(this, param);
        methodInvocation.getArguments().forEach(arg -> arg.accept(this, param));
        return null;
    }

    @Override
    public R visiExpressionStatement(final ExpressionStatement expressionStatement, final P param) {
        return expressionStatement.getExpression().accept(this, param);
    }

    @Override
    public R visitAnnotatedType(final AnnotatedTypeTree annotatedType, final P param) {
        annotatedType.getAnnotations().forEach(a -> a.accept(this, param));
        annotatedType.getClazz().accept(this, param);
        annotatedType.getArguments().forEach(a -> a.accept(this, param));
        return null;
    }

    @Override
    public R visitVariableDeclaratorStatement(final VariableDeclarator variableDeclaratorStatement, final P param) {
        variableDeclaratorStatement.getName().accept(this, param);

        if (variableDeclaratorStatement.getValue() != null) {
            variableDeclaratorStatement.getValue().accept(this, param);
        }

        variableDeclaratorStatement.getType().accept(this, param);

        return null;
    }

    @Override
    public R visitUnaryExpression(final UnaryExpressionTree unaryExpression, final P param) {
        unaryExpression.getExpression().accept(this, param);
        return null;
    }

    @Override
    public R visitWildCardExpression(final WildcardExpressionTree wildCardExpression, final P param) {
        if (wildCardExpression.getBound() != null) {
            wildCardExpression.getBound().accept(this, param);
        }
        return null;
    }

    @Override
    public R visitIfStatement(final IfStatementTree ifStatementTree, final P param) {
        ifStatementTree.getExpression().accept(this, param);
        ifStatementTree.getThenStatement().accept(this, param);

        if (ifStatementTree.getElseStatement() != null) {
            ifStatementTree.getElseStatement().accept(this, param);
        }

        return null;
    }

    @Override
    public R visitForStatement(final ForStatement forStatement, final P param) {
        accept(forStatement.getForInit(), param);
        accept(forStatement.getExpression(), param);
        accept(forStatement.getForUpdate(), param);
        forStatement.getStatement().accept(this, param);
        return null;
    }

    @Override
    public R visitEnhancedForStatement(final EnhancedForStatement enhancedForStatement, final P param) {
        enhancedForStatement.getExpression().accept(this, param);
        enhancedForStatement.getLocalVariable().accept(this, param);
        enhancedForStatement.getStatement().accept(this, param);
        return null;
    }

    private void accept(final Tree tree,
                        final P param) {
        if (tree != null) {
            tree.accept(this, param);
        }
    }

    @Override
    public R visitNewClass(final NewClassExpression newClassExpression, final P param) {
        newClassExpression.getName().accept(this, param);
        newClassExpression.getBody().accept(this, param);
        return null;
    }

    @Override
    public R visitWhileStatement(final WhileStatement whileStatement, final P param) {
        whileStatement.getCondition().accept(this, param);
        whileStatement.getBody().accept(this, param);
        return null;
    }

    @Override
    public R visitDoWhileStatement(final DoWhileStatement doWhileStatement, final P param) {
        doWhileStatement.getBody().accept(this, param);
        doWhileStatement.getCondition().accept(this, param);
        return null;
    }

    @Override
    public R visitAnnotation(final AnnotationTree annotationTree, final P param) {
        annotationTree.getName().accept(this, param);
        annotationTree.getArguments().forEach(arg -> arg.accept(this, param));
        return null;
    }

    @Override
    public R visitAssignment(final AssignmentExpression assignmentExpression, final P param) {
        assignmentExpression.getLeft().accept(this, param);
        assignmentExpression.getRight().accept(this, param);
        return null;
    }
}

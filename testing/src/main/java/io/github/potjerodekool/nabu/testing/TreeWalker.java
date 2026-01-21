package io.github.potjerodekool.nabu.testing;

import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.statement.*;

import java.util.function.Consumer;

public class TreeWalker extends AbstractTreeVisitor<Object, Consumer<Tree
        >> implements TreeVisitor<Object, Consumer<Tree>> {

    private static final TreeWalker INSTANCE = new TreeWalker();

    private TreeWalker() {
    }

    public static void walk(final Tree tree,
                            final Consumer<Tree> consumer) {
        INSTANCE.acceptTree(tree, consumer);
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Consumer<Tree> consumer) {
        consumer.accept(compilationUnit);
        compilationUnit.getClasses()
                .forEach(classDeclaration -> visitClass(classDeclaration, consumer));
        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Consumer<Tree> consumer) {
        consumer.accept(classDeclaration);
        classDeclaration.getEnclosedElements().forEach(element ->
                acceptTree(element, consumer));
        return null;
    }

    @Override
    public Object visitFunction(final Function function,
                                final Consumer<Tree> consumer) {
        consumer.accept(function);
        acceptTree(function.getBody(), consumer);
        return null;
    }

    @Override
    public Object visitBlockStatement(final BlockStatementTree blockStatement,
                                      final Consumer<Tree> consumer) {
        consumer.accept(blockStatement);
        blockStatement.getStatements().forEach(statementTree ->
                acceptTree(statementTree, consumer));
        return null;
    }

    @Override
    public Object visitIfStatement(final IfStatementTree ifStatementTree,
                                   final Consumer<Tree> consumer) {
        consumer.accept(ifStatementTree);
        acceptTree(ifStatementTree.getExpression(), consumer);
        acceptTree(ifStatementTree.getThenStatement(), consumer);

        if (ifStatementTree.getElseStatement() != null) {
            acceptTree(ifStatementTree.getElseStatement(), consumer);
        }

        return null;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                   final Consumer<Tree> consumer) {
        consumer.accept(variableDeclaratorStatement);

        if (variableDeclaratorStatement.getValue() != null) {
            acceptTree(variableDeclaratorStatement.getValue(), consumer);
        }

        return null;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final Consumer<Tree> consumer) {
        consumer.accept(identifier);
        return null;
    }

    @Override
    public Object visitReturnStatement(final ReturnStatementTree returnStatement,
                                       final Consumer<Tree> consumer) {
        consumer.accept(returnStatement);
        if (returnStatement.getExpression() != null) {
            acceptTree(returnStatement.getExpression(), consumer);
        }

        return null;
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                        final Consumer<Tree> consumer) {
        consumer.accept(lambdaExpression);
        lambdaExpression.getVariables().forEach(variable -> acceptTree(variable, consumer));
        acceptTree(lambdaExpression.getBody(), consumer);
        return null;
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpressionTree binaryExpression,
                                        final Consumer<Tree> consumer) {
        consumer.accept(binaryExpression);
        acceptTree(binaryExpression.getLeft(), consumer);
        acceptTree(binaryExpression.getRight(), consumer);
        return null;
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                             final Consumer<Tree> consumer) {
        consumer.accept(fieldAccessExpression);

        if (fieldAccessExpression.getSelected() != null) {
            acceptTree(fieldAccessExpression.getSelected(), consumer);
        }
        acceptTree(fieldAccessExpression.getField(), consumer);
        return null;
    }

    @Override
    public Object visitExpressionStatement(final ExpressionStatementTree expressionStatement,
                                           final Consumer<Tree> consumer) {
        consumer.accept(expressionStatement);
        acceptTree(expressionStatement.getExpression(), consumer);

        return null;
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                        final Consumer<Tree> consumer) {
        consumer.accept(methodInvocation);
        acceptTree(methodInvocation.getMethodSelector(), consumer);
        methodInvocation.getArguments().forEach(arg ->
                acceptTree(arg, consumer));
        return null;
    }

    @Override
    public Object visitCastExpression(final CastExpressionTree castExpressionTree,
                                      final Consumer<Tree> consumer) {
        consumer.accept(castExpressionTree);
        acceptTree(castExpressionTree.getTargetType(), consumer);
        acceptTree(castExpressionTree.getExpression(), consumer);
        return null;
    }

    @Override
    public Object visitTypeIdentifier(final TypeApplyTree typeIdentifier,
                                      final Consumer<Tree> consumer) {
        acceptTree(typeIdentifier.getClazz(), consumer);

        typeIdentifier.getTypeParameters().forEach(typeParameter ->
                acceptTree(typeParameter, consumer));
        consumer.accept(typeIdentifier);
        return null;
    }

    @Override
    public Object visitLiteralExpression(final LiteralExpressionTree literalExpression,
                                         final Consumer<Tree> consumer) {
        consumer.accept(literalExpression);
        return null;
    }

    @Override
    public Object visitUnknown(final Tree tree,
                               final Consumer<Tree> consumer) {
        throw new TodoException();
    }
}

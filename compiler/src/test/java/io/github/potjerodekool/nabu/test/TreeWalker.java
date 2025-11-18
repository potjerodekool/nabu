package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.IfStatementTree;
import io.github.potjerodekool.nabu.tree.statement.ReturnStatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

import java.util.function.Consumer;

public class TreeWalker implements TreeVisitor<Object, Consumer<Tree>> {

    private static final TreeWalker INSTANCE = new TreeWalker();

    private TreeWalker() {
    }

    public static void walk(final Tree tree,
                            final Consumer<Tree> consumer) {
        tree.accept(INSTANCE, consumer);
    }

    @Override
    public Object visitFunction(final Function function, final Consumer<Tree> consumer) {
        function.getBody().accept(this, consumer);
        return null;
    }

    @Override
    public Object visitBlockStatement(final BlockStatementTree blockStatement,
                                      final Consumer<Tree> consumer) {
        blockStatement.getStatements().forEach(statementTree -> statementTree.accept(this, consumer));
        return null;
    }

    @Override
    public Object visitIfStatement(final IfStatementTree ifStatementTree,
                                   final Consumer<Tree> consumer) {
        ifStatementTree.getExpression().accept(this, consumer);
        ifStatementTree.getThenStatement().accept(this, consumer);
        
        
        if (ifStatementTree.getElseStatement() != null) {
            ifStatementTree.getElseStatement().accept(this, consumer);
        }
        
        return null;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final Consumer<Tree> consumer) {
        consumer.accept(variableDeclaratorStatement);;
        return null;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier, final Consumer<Tree> consumer) {
        consumer.accept(identifier);
        return null;
    }

    @Override
    public Object visitReturnStatement(final ReturnStatementTree returnStatement, final Consumer<Tree> consumer) {
        consumer.accept(returnStatement);
        if (returnStatement.getExpression() != null) {
            returnStatement.getExpression().accept(this, consumer);
        }

        return null;
    }

    @Override
    public Object visitUnknown(final Tree tree,
                               final Consumer<Tree> consumer) {
        throw new TodoException();
    }
}

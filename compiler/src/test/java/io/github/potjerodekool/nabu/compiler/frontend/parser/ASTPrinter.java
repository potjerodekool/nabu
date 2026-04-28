package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.testing.IndentPrinter;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.impl.CParenthesizedExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.ExpressionStatementTree;
import io.github.potjerodekool.nabu.tree.statement.ReturnStatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

public final class ASTPrinter {

    private final IndentPrinter printer = new IndentPrinter();

    private ASTPrinter() {
    }

    public static String print(final Tree tree) {
        final var astPrinter = new ASTPrinter();
        astPrinter.printTree(tree);
        return astPrinter.printer.getText();
    }

    private void printTree(final Tree tree) {
        switch (tree) {
            case ClassDeclaration classDeclaration -> printClassDeclaration(classDeclaration);
            case Function function -> printFunction(function);
            case BlockStatementTree blockStatementTree -> printBlock(blockStatementTree);
            case VariableDeclaratorTree variableDeclaratorTree -> printVariableDeclarator(variableDeclaratorTree);
            case CastExpressionTree castExpressionTree -> printCastExpressionTree(castExpressionTree);
            case MethodInvocationTree methodInvocationTree -> printMethodInvocationTree(methodInvocationTree);
            case ExpressionStatementTree expressionStatementTree -> printExpressionStatementTree(expressionStatementTree);
            case ReturnStatementTree returnStatementTree -> printReturnStatementTree(returnStatementTree);
            case BinaryExpressionTree binaryExpressionTree -> printBinaryExpressionTree(binaryExpressionTree);
            case IdentifierTree identifierTree -> printIdentifierTree(identifierTree);
            case FieldAccessExpressionTree fieldAccessExpressionTree -> printFieldAccessExpressionTree(fieldAccessExpressionTree);
            case ParenthesizedExpression p -> printParenthesizedExpression(p);
            case LiteralExpressionTree l -> printLiteralExpressionTree(l);
            default -> throw new TodoException("" + tree);
        }
    }

    private void printLiteralExpressionTree(final LiteralExpressionTree l) {
        printLn("LITERAL_EXPRESSION " + positionInfo(l));
    }

    private void printParenthesizedExpression(final ParenthesizedExpression p) {
        printLn("PARENTHESIZED_EXPRESSION " + positionInfo(p));
        printer.incrementTabs();
        printTree(p.getExpression());
        printer.decrementTabs();
    }

    private void printFieldAccessExpressionTree(final FieldAccessExpressionTree fieldAccessExpressionTree) {
        printLn("FIELD_ACCESS_EXPRESSION " + positionInfo(fieldAccessExpressionTree));
        printer.incrementTabs();
        printTree(fieldAccessExpressionTree.getSelected());
        printTree(fieldAccessExpressionTree.getField());
        printer.decrementTabs();
    }

    private void printIdentifierTree(final IdentifierTree identifierTree) {
        printLn("IDENTIFIER(%s)".formatted(identifierTree.getName()) + positionInfo(identifierTree));
    }

    private void printBinaryExpressionTree(final BinaryExpressionTree binaryExpressionTree) {
        printLn("BINARY_EXPRESSION " + positionInfo(binaryExpressionTree));
        printer.incrementTabs();
        printTree(binaryExpressionTree.getLeft());
        printLn(binaryExpressionTree.getTag().toString());
        printTree(binaryExpressionTree.getRight());
        printer.decrementTabs();
    }

    private void printReturnStatementTree(final ReturnStatementTree returnStatementTree) {
        printLn("RETURN_STATEMENT " + positionInfo(returnStatementTree));
    }

    private void printExpressionStatementTree(final ExpressionStatementTree expressionStatementTree) {
        printLn("EXPRESSION_STATEMENT " + positionInfo(expressionStatementTree));
        printTree(expressionStatementTree.getExpression());
    }

    private void printMethodInvocationTree(final MethodInvocationTree methodInvocationTree) {
        printLn("METHOD_INVOCATION " + positionInfo(methodInvocationTree));
        printer.incrementTabs();
        printTree(methodInvocationTree.getMethodSelector());
        methodInvocationTree.getArguments().forEach(this::printTree);
        printer.decrementTabs();
    }

    private void printCastExpressionTree(final CastExpressionTree castExpressionTree) {
        printLn("CAST_EXPRESSION " + positionInfo(castExpressionTree));
        printer.incrementTabs();
        printTree(castExpressionTree.getTargetType());
        printTree(castExpressionTree.getExpression());
        printer.decrementTabs();
    }

    private void printVariableDeclarator(final VariableDeclaratorTree variableDeclaratorTree) {
        printLn("VARIABLE_DECLARATOR " + positionInfo(variableDeclaratorTree));
    }

    private void printClassDeclaration(final ClassDeclaration classDeclaration) {
        printLn("CLASS_DECLARATION " + positionInfo(classDeclaration));
        printer.incrementTabs();
        classDeclaration.getEnclosedElements().forEach(this::printTree);
        printer.decrementTabs();
    }

    private void printFunction(final Function function) {
        printLn("FUNCTION " + positionInfo(function));

        if (function.getBody() != null) {
            printTree(function.getBody());
        }
    }

    private void printBlock(final BlockStatementTree block) {
        printer.incrementTabs();
        block.getStatements().forEach(this::printTree);
        printer.decrementTabs();
    }

    private String positionInfo(final Tree tree) {
        final var line = tree.getLineNumber();
        final var column = tree.getColumnNumber();
        return String.format("[%s,%s]", line, column);
    }

    private void print(final String text) {
        printer.write(text);
    }

    private void printLn(final String text) {
        printer.writeLine(text);
    }

    private void printLn() {
        printer.newLine();
    }
}

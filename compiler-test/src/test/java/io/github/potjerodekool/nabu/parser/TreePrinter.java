package io.github.potjerodekool.nabu.parser;

import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CAnnotatedType;
import io.github.potjerodekool.nabu.compiler.tree.expression.CTypeApply;

import java.io.StringWriter;
import java.util.List;

public class TreePrinter extends AbstractTreeVisitor<Object, Object> {

    private final StringWriter writer = new StringWriter();

    @Override
    public Object visitFunction(final CFunction function, final Object param) {
        write("fun ").write(function.getSimpleName());
        write("(");

        writeList(function.getParameters(), param);
        write("): ");

        if (function.getReturnType() != null) {
            function.getReturnType().accept(this, param);
        }

        write(" ");

        if (function.getBody() != null) {
            function.getBody().accept(this, param);
        }

        newLine();
        return null;
    }

    @Override
    public Object visitVariable(final CVariable variable, final Object param) {
        write(variable.getSimpleName()).write(" : ");
        variable.getType().accept(this, param);
        return null;
    }

    @Override
    public Object visitBlockStatement(final BlockStatement blockStatement,
                                        final Object param) {
        write("{");
        blockStatement.getStatements().forEach(statement -> statement.accept(this, param));
        write("}").newLine();

        return null;
    }

    @Override
    public Object visitReturnStatement(final ReturnStatement returnStatement,
                                         final Object param) {
        write("return ");
        returnStatement.getExpression().accept(this, param);
        write(";");

        return null;
    }

    @Override
    public Object visitIdentifier(final CIdent ident, final Object param) {
        write(ident.getName());
        return null;
    }

    @Override
    public Object visitLambdaExpression(final CLambdaExpression lambdaExpression, final Object param) {
        write("(");
        writeList(lambdaExpression.getVariables(),param);
        write(")");
        write(" -> ");

        lambdaExpression.getBody().accept(this, param);
        return null;
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpression binaryExpression,
                                          final Object param) {
        binaryExpression.getLeft().accept(this, param);
        write(" ");

        write(binaryExpression.getOperator().getText());

        write(" ");
        binaryExpression.getRight().accept(this, param);

        return null;
    }

    @Override
    public Object visitFieldAccessExpression(final CFieldAccessExpression fieldAccessExpression, final Object param) {
        fieldAccessExpression.getTarget().accept(this, param);
        write(".");
        fieldAccessExpression.getField().accept(this, param);
        return null;
    }

    private TreePrinter write(final String text) {
        writer.write(text);
        writer.flush();
        return this;
    }

    private TreePrinter writeLine(final String text) {
        write(text);
        newLine();
        return this;
    }

    private TreePrinter newLine() {
        writer.write("\n");
        writer.flush();
        return this;
    }

    private TreePrinter writeList(final List<? extends Tree> list,
                                  final Object param,
                                  final String sep) {
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                write(sep);
            }
            list.get(i).accept(this, param);
        }

        return this;
    }

    private TreePrinter writeList(final List<? extends Tree> list,
                                  final Object param) {
        return writeList(list, param, ", ");
    }

    @Override
    public Object visitTypeIdentifier(final CTypeApply typeIdentifier, final Object param) {
        write(typeIdentifier.getName());
        return null;
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocation methodInvocation, final Object param) {
        if (methodInvocation.getTarget() != null) {
            methodInvocation.getTarget().accept(this, param);
            write(".");
        }

        methodInvocation.getName().accept(this, param);

        write("(");
        writeList(methodInvocation.getArguments(), param);
        write(")");

        return null;
    }

    @Override
    public Object visitLiteralExpression(final LiteralExpression literalExpression, final Object param) {
        final var literal = literalExpression.getLiteral();

        switch (literalExpression.getLiteralKind()) {
            case STRING -> write("\"" + literal + "\"");
            case NULL -> write("null");
        }
        return null;
    }

    @Override
    public Object visitStatementExpression(final StatementExpression statementExpression, final Object param) {
        statementExpression.getExpression().accept(this, param);
        return null;
    }

    @Override
    public Object visitAnnotatedType(final CAnnotatedType annotatedType, final Object param) {
        annotatedType.getClazz().accept(this, param);
        write("<");
        writeList(annotatedType.getArguments(), param);
        write(">");
        return null;
    }

    @Override
    public Object visitClass(final CClassDeclaration classDeclaration, final Object param) {
        final var className = classDeclaration.getSimpleName();
        write("public class ");
        write(className);
        writeLine(" {");
        classDeclaration.getEnclosedElements().forEach(e -> e.accept(this, param));
        writeLine("}");
        return null;
    }

    public String getText() {
        return writer.toString();
    }
}

package io.github.potjerodekool.nabu.compiler.enhance;

import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.ExpressionStatementTree;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

public class Enhancer extends AbstractTreeVisitor<Object, Object> {

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration, final Object param) {
        //conditionalGenerateConstructor(classDeclaration);
        //conditionalInvokeSuper(classDeclaration);
        return super.visitClass(classDeclaration, param);
    }

    private void conditionalGenerateConstructor(final ClassDeclaration classDeclaration) {
        if (classDeclaration.getKind() == Kind.INTERFACE
                || classDeclaration.getKind() == Kind.ENUM) {
            return;
        }

        if (!hasConstructor(classDeclaration)) {
            final var statements = new ArrayList<StatementTree>();

            final var superCall = TreeMaker.methodInvocationTree(
                    IdentifierTree.create(Constants.THIS),
                    IdentifierTree.create(Constants.SUPER),
                    List.of(),
                    List.of(),
                    classDeclaration.getLineNumber(),
                    classDeclaration.getColumnNumber()
            );

            statements.add(TreeMaker.expressionStatement(superCall, superCall.getLineNumber(), superCall.getColumnNumber()));
            statements.add(TreeMaker.returnStatement(null, -1, -1));

            final var accessFlag = Flags.PUBLIC;

            final var body = TreeMaker.blockStatement(
                    statements,
                    -1,
                    -1
            );

            final var constructor = new FunctionBuilder()
                    .simpleName(Constants.INIT)
                    .kind(Kind.CONSTRUCTOR)
                    .returnType(TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.VOID, -1, -1))
                    .modifiers(new CModifiers(List.of(), accessFlag))
                    .body(body)
                    .build();

            classDeclaration.enclosedElement(constructor, 0);
        }
    }

    private void conditionalInvokeSuper(final ClassDeclaration classDeclaration) {
        classDeclaration.getEnclosedElements().stream()
                .flatMap(CollectionUtils.mapOnly(Function.class))
                .filter(function -> function.getKind() == Kind.CONSTRUCTOR)
                .forEach(this::conditionalInvokeSuper);
    }

    private void conditionalInvokeSuper(final Function constructor) {
        final var body = constructor.getBody();

        final var newStatements = new ArrayList<StatementTree>();
        boolean hasConstructorInvocation = false;

        for (final var statement : body.getStatements()) {
            if (hasConstructorInvocation) {
                newStatements.add(statement);
            } else {
                if (!isConstructorInvocation(statement)) {
                    final var constructorInvocation = TreeMaker.methodInvocationTree(
                            null,
                            IdentifierTree.create(Constants.SUPER),
                            List.of(),
                            List.of(),
                            -1,
                            -1
                    );

                    newStatements.add(TreeMaker.expressionStatement(constructorInvocation, -1, -1));

                }
                hasConstructorInvocation = true;
                newStatements.add(statement);
            }
        }

        if (newStatements.size() > body.getStatements().size()) {
            final var newBody = body.builder()
                    .statements(newStatements)
                    .build();

            constructor.setBody(newBody);
        }
    }

    private boolean isConstructorInvocation(final StatementTree statement) {
        return statement instanceof ExpressionStatementTree expressionStatement
                && expressionStatement.getExpression() instanceof MethodInvocationTree methodInvocationTree
                && isThisOrSuper(methodInvocationTree.getName());
    }

    private boolean isThisOrSuper(final ExpressionTree expressionTree) {
        if (expressionTree instanceof IdentifierTree identifierTree) {
            return Constants.THIS.equals(identifierTree.getName())
                    || Constants.SUPER.equals(identifierTree.getName());
        } else if (expressionTree instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            final var target = fieldAccessExpressionTree.getTarget();

            if (!(target instanceof IdentifierTree identifierTree)) {
                return false;
            }

            if (!Constants.THIS.equals(identifierTree.getName())) {
                return false;
            }

            if (!(fieldAccessExpressionTree.getField() instanceof IdentifierTree field)) {
                return false;
            }

            return Constants.SUPER.equals(field.getName());
        }

        return false;
    }


    private boolean hasConstructor(final ClassDeclaration classDeclaration) {
        return classDeclaration.getEnclosedElements().stream()
                .flatMap(CollectionUtils.mapOnly(Function.class))
                .anyMatch(e -> e.getKind() == Kind.CONSTRUCTOR);
    }
}

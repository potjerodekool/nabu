package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclarator;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;

public class LambdaToMethod extends AbstractTreeVisitor<Object, SimpleScope> {

    public LambdaToMethod() {
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final SimpleScope scope) {
        final var classContext = new LambdaContext();
        final var classScope = new SimpleScope(classDeclaration, classContext);

        final var enclosedElements = new ArrayList<>(classDeclaration.getEnclosedElements());

        enclosedElements.forEach(enclosedElement ->
                enclosedElement.accept(this, classScope)
        );

        return null;
    }

    @Override
    public Object visitFunction(final Function function, final SimpleScope scope) {
        final var functionScope = scope.childScope(function);
        super.visitFunction(function, functionScope);
        return null;
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                        final SimpleScope scope) {
        final var classDeclaration = scope.getCurrentClassDeclaration();
        final var currentFunction = scope.getCurrentFunctionDeclaration();
        final var classSymbol = (ClassSymbol) classDeclaration.getClassSymbol();

        lambdaExpression.getVariables().forEach(
                variable -> variable.accept(this, scope)
        );

        lambdaExpression.getBody().accept(this, new ImmutableScope(scope));

        final var method = (MethodSymbol) createLambdaMethod(
                scope,
                currentFunction,
                lambdaExpression,
                classSymbol
        );

        classSymbol.addEnclosedElement(method);

        final var lambdaFunction = createLambdaFunction(
                method,
                lambdaExpression
        );

        classDeclaration.enclosedElement(lambdaFunction);
        return null;
    }

    private ExecutableElement createLambdaMethod(final SimpleScope scope,
                                                 final Function currentFunction,
                                                 final LambdaExpressionTree lambdaExpression,
                                                 final TypeElement classSymbol) {
        final var context = scope.getLambdaContext();

        final var lambdaFunctionName = context.generateLambdaMethodName(
                currentFunction.getSimpleName()
        );

        final var lambdaType = (DeclaredType) lambdaExpression.getType();
        final var lambdaClass = (TypeElement) lambdaType.asElement();

        final var method = new MethodBuilder()
                .name(lambdaFunctionName)
                .enclosingElement(classSymbol)
                .returnType(lambdaClass.findFunctionalMethod().getReturnType())
                .modifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.SYNTHETIC)
                .build();

        addParameters(scope, method);

        lambdaExpression.setLambdaMethodType((ExecutableType) method.asType());

        return method;
    }

    private void addParameters(final Scope scope,
                               final ExecutableElement executableElement) {
        final var method = (MethodSymbol) executableElement;

        final var locals = scope.locals();

        locals.forEach(localName -> {
            final var originalElement = scope.resolve(localName);
            final var typeMirror = originalElement.asType();

            final var parameterElement = new VariableBuilder()
                    .kind(ElementKind.PARAMETER)
                    .name(localName)
                    .type(typeMirror)
                    .enclosingElement(method)
                    .build();

            method.addParameter(parameterElement);

            final var param = TreeMaker.variableDeclarator(
                    Kind.PARAMETER,
                    new CModifiers(),
                    createTypeExpression(originalElement.asType()),
                    IdentifierTree.create(originalElement.getSimpleName()),
                    null,
                    null,
                    -1,
                    -1
            );

            param.getName().setSymbol(parameterElement);
            param.getType().setType(originalElement.asType());
        });
    }

    private Function createLambdaFunction(final ExecutableElement method,
                                          final LambdaExpressionTree lambdaExpression) {
        final var parameters = createParameters(
                method
        );

        final var lambdaBody = asBlockStatement(lambdaExpression.getBody());

        return new FunctionBuilder()
                .kind(Kind.METHOD)
                .simpleName(method.getSimpleName())
                .parameters(parameters)
                .returnType(createTypeExpression(method.getReturnType()))
                .method(method)
                .body(lambdaBody)
                .build();
    }

    private List<VariableDeclarator> createParameters(final ExecutableElement method) {
        final var methodParameters = method.getParameters();
        return methodParameters.stream()
                .map(parameterElement -> {
                    final var parameter = TreeMaker.variableDeclarator(
                            Kind.PARAMETER,
                            new CModifiers(),
                            createTypeExpression(parameterElement.asType()),
                            IdentifierTree.create(parameterElement.getSimpleName()),
                            null,
                            null,
                            -1,
                            -1
                    );

                    parameter.getType().setType(parameterElement.asType());
                    parameter.getName().setSymbol(parameterElement);
                    return parameter;
                })
                .toList();
    }

    private BlockStatement asBlockStatement(final Statement statement) {
        if (statement instanceof BlockStatement blockStatement) {
            return blockStatement;
        } else {
            return TreeMaker.blockStatement(List.of(statement), -1, -1);
        }
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclarator variableDeclaratorStatement,
                                                   final SimpleScope scope) {
        if (scope != null) {
            scope.define(variableDeclaratorStatement.getName().getSymbol());
        }

        return null;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final SimpleScope scope) {
        final var name = identifier.getName();
        scope.resolve(name);
        return null;
    }

    private ExpressionTree createTypeExpression(final TypeMirror typeMirror) {
        final var creator = new TypeExpressionCreator();
        return typeMirror.accept(creator, null);
    }
}


class ImmutableScope extends SimpleScope {

    public ImmutableScope(final SimpleScope parentScope) {
        super(parentScope, parentScope.getOwner(), parentScope.getLambdaContext());
    }

    @Override
    public void define(final Element element) {
    }
}
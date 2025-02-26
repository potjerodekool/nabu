package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Variable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;

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
        final var classSymbol = classDeclaration.classSymbol;

        lambdaExpression.getVariables().forEach(
                variable -> variable.accept(this, scope)
        );

        lambdaExpression.getBody().accept(this, scope);

        final var method = createLambdaMethod(
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
                .modifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.SYNTHENTIC)
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

            final var parameter = new Variable(
                    -1,
                    -1
            );
            parameter.kind(Element.Kind.PARAMETER);
            parameter.simpleName(originalElement.getSimpleName());
            parameter.type(createTypeExpression(originalElement.asType()));
            parameter.getType().setType(originalElement.asType());

            parameter.setVarSymbol(parameterElement);
        });
    }

    private Function createLambdaFunction(final ExecutableElement method,
                                          final LambdaExpressionTree lambdaExpression) {
        final var lambdaFunction = new Function(
                -1,
                -1
        );
        lambdaFunction.simpleName(method.getSimpleName());

        addParameters(
                method,
                lambdaFunction
        );

        lambdaFunction.returnType(createTypeExpression(method.getReturnType()));
        lambdaFunction.methodSymbol = method;

        final var lambdaBody = asBlockStatement(lambdaExpression.getBody());
        lambdaFunction.body(lambdaBody);

        return lambdaFunction;
    }

    private void addParameters(final ExecutableElement method,
                               final Function lambdaFunction) {
        final var methodParameters = method.getParameters();

        methodParameters.forEach(parameterElement -> {

            final var parameter = new Variable(
                    -1,
                    -1
            );
            parameter.kind(Element.Kind.PARAMETER);
            parameter.simpleName(parameterElement.getSimpleName());
            parameter.type(createTypeExpression(parameterElement.asType()));
            parameter.getType().setType(parameterElement.asType());

            parameter.setVarSymbol(parameterElement);
            lambdaFunction.parameter(parameter);
        });
    }

    private BlockStatement asBlockStatement(final Statement statement) {
        if (statement instanceof BlockStatement blockStatement) {
            return blockStatement;
        } else {
            return new BlockStatement().statement(statement);
        }
    }

    @Override
    public Object visitVariable(final Variable variable,
                                final SimpleScope scope) {
        if (scope != null) {
            scope.define(variable.getVarSymbol());
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


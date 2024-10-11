package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CElement;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;

public class LambdaToMethod extends AbstractTreeVisitor<Object, SimpleScope> {

    private final TypeCloner cloner;

    public LambdaToMethod(final ClassElementLoader loader) {
        this.cloner = new TypeCloner(loader.getTypes());
    }

    @Override
    public Object visitClass(final CClassDeclaration classDeclaration,
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
    public Object visitFunction(final CFunction function, final SimpleScope scope) {
        final var functionScope = scope.childScope(function);
        super.visitFunction(function, functionScope);
        return null;
    }

    @Override
    public Object visitLambdaExpression(final CLambdaExpression lambdaExpression,
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

    private MethodSymbol createLambdaMethod(final SimpleScope scope,
                                            final CFunction currentFunction,
                                            final CLambdaExpression lambdaExpression,
                                            final ClassSymbol classSymbol) {
        final var context = scope.getLambdaContext();

        final var lambdaFunctionName = context.generateLambdaMethodName(
                currentFunction.getSimpleName()
        );

        final var lambdaType = (ClassType) lambdaExpression.getType();
        final var lambdaClass = (ClassSymbol) lambdaType.asElement();

        final var method = new MethodSymbol(
                ElementKind.METHOD,
                lambdaFunctionName,
                classSymbol
        );

        method.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.SYNTHENTIC);

        method.getMethodType()
                .setReturnType(cloneType(lambdaClass.findFunctionalMethod().getMethodType().getReturnType()));

        addParameters(scope, method);

        lambdaExpression.setLambdaMethodType(method.getMethodType());

        return method;
    }

    private void addParameters(final Scope scope,
                               final MethodSymbol method) {
        final var locals = scope.locals();

        locals.forEach(localName -> {
            final var originalElement = (VariableElement) scope.resolve(localName);
            final var typeMirror = cloneType(originalElement.getVariableType());

            final var parameterElement = new VariableElement(ElementKind.PARAMETER, localName, method);
            parameterElement.setVariableType(typeMirror);
            method.addParameter(parameterElement);

            final var parameter = new CVariable();
            parameter.kind(CElement.Kind.PARAMETER);
            parameter.simpleName(originalElement.getSimpleName());
            parameter.type(createTypeExpression(originalElement.getVariableType()));
            parameter.getType().setType(originalElement.getVariableType());

            parameter.setVarSymbol(parameterElement);
        });
    }

    private CFunction createLambdaFunction(final MethodSymbol method,
                                           final CLambdaExpression lambdaExpression) {
        final var lambdaFunction = new CFunction();
        lambdaFunction.simpleName(method.getSimpleName());

        addParameters(
                method,
                lambdaFunction
        );

        lambdaFunction.returnType(createTypeExpression(method.getMethodType().getReturnType()));
        lambdaFunction.methodSymbol = method;

        final var lambdaBody = asBlockStatement(lambdaExpression.getBody());
        lambdaFunction.body(lambdaBody);

        return lambdaFunction;
    }

    private void addParameters(final MethodSymbol method,
                               final CFunction lambdaFunction) {
        final var methodParameters = method.getParameters();

        methodParameters.forEach(parameterElement -> {

            final var parameter = new CVariable();
            parameter.kind(CElement.Kind.PARAMETER);
            parameter.simpleName(parameterElement.getSimpleName());
            parameter.type(createTypeExpression(parameterElement.getVariableType()));
            parameter.getType().setType(parameterElement.getVariableType());

            parameter.setVarSymbol(parameterElement);
            lambdaFunction.parameter(parameter);
        });
    }

    private TypeMirror cloneType(final TypeMirror typeMirror) {
        return typeMirror.accept(cloner, null);
    }

    private BlockStatement asBlockStatement(final Statement statement) {
        if (statement instanceof BlockStatement blockStatement) {
            return blockStatement;
        } else {
            return new BlockStatement().statement(statement);
        }
    }

    @Override
    public Object visitVariable(final CVariable variable,
                                final SimpleScope scope) {
        if (scope != null) {
            scope.define(variable.getVarSymbol());
        }

        return null;
    }

    @Override
    public Object visitIdentifier(final CIdent ident,
                                  final SimpleScope scope) {
        final var name = ident.getName();
        scope.resolve(name);
        return null;
    }

    private CExpression createTypeExpression(final TypeMirror typeMirror) {
        final var creator = new TypeExpressionCreator();
        return typeMirror.accept(creator, null);
    }
}


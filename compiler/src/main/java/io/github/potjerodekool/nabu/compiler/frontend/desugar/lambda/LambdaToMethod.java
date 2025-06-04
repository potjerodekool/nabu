package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.Modifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class LambdaToMethod extends AbstractTreeVisitor<Object, LambdaScope> {

    public LambdaToMethod() {
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final LambdaScope scope) {
        final var classContext = new LambdaContext();
        final var classScope = new SimpleScope(classDeclaration, classContext);

        final var enclosedElements = new ArrayList<>(classDeclaration.getEnclosedElements());

        enclosedElements.forEach(enclosedElement ->
                enclosedElement.accept(this, classScope)
        );

        return null;
    }

    @Override
    public Object visitFunction(final Function function,
                                final LambdaScope scope) {
        final var functionScope = scope.childScope(function);
        super.visitFunction(function, functionScope);
        return null;
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                        final LambdaScope scope) {
        final var classDeclaration = scope.getCurrentClassDeclaration();
        final var currentFunction = scope.getCurrentFunctionDeclaration();
        final var classSymbol = (ClassSymbol) classDeclaration.getClassSymbol();

        lambdaExpression.getVariables().forEach(
                variable -> variable.accept(this, scope)
        );

        lambdaExpression.getBody().accept(this, new ImmutableScope(
                scope,
                scope.getOwner()
        ));

        final var method = (MethodSymbol) createLambdaMethod(
                scope,
                currentFunction.getSimpleName(),
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

    private ExecutableElement createLambdaMethod(final LambdaScope scope,
                                                 final String functionName,
                                                 final LambdaExpressionTree lambdaExpression,
                                                 final ClassSymbol classSymbol) {
        final var context = scope.getLambdaContext();

        final var lambdaFunctionName = context.generateLambdaMethodName(
                functionName
        );

        final var lambdaType = (DeclaredType) lambdaExpression.getType();
        final var lambdaClass = (TypeElement) lambdaType.asElement();

        final var method = new MethodSymbolBuilderImpl()
                .simpleName(lambdaFunctionName)
                .enclosingElement(classSymbol)
                .returnType(lambdaClass.findFunctionalMethod().getReturnType())
                .flags(
                        Flags.PRIVATE + Flags.STATIC + Flags.SYNTHETIC
                )
                .build();

        addParameters(scope, method);

        lambdaExpression.setLambdaMethodType(method.asType());

        return method;
    }

    private void addParameters(final LambdaScope scope,
                               final ExecutableElement executableElement) {
        final var method = (MethodSymbol) executableElement;

        final var locals = scope.locals();

        locals.forEach(localName -> {
            final var originalElement = scope.resolve(localName);
            final var typeMirror = originalElement.asType();

            final var parameterElement = new VariableSymbolBuilderImpl()
                    .kind(ElementKind.PARAMETER)
                    .simpleName(localName)
                    .type(typeMirror)
                    .enclosingElement(method)
                    .build();

            method.addParameter(parameterElement);

            final var param = TreeMaker.variableDeclarator(
                    Kind.PARAMETER,
                    new Modifiers(),
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

    private List<VariableDeclaratorTree> createParameters(final ExecutableElement method) {
        final var methodParameters = method.getParameters();
        return methodParameters.stream()
                .map(parameterElement -> {
                    final var parameter = TreeMaker.variableDeclarator(
                            Kind.PARAMETER,
                            new Modifiers(),
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

    private BlockStatementTree asBlockStatement(final StatementTree statement) {
        if (statement instanceof BlockStatementTree blockStatement) {
            return blockStatement;
        } else {
            return TreeMaker.blockStatement(List.of(statement), -1, -1);
        }
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                   final LambdaScope scope) {
        if (scope != null) {
            scope.define(variableDeclaratorStatement.getName().getSymbol());
        }

        return null;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final LambdaScope scope) {
        final var name = identifier.getName();
        scope.resolve(name);
        return null;
    }

    private ExpressionTree createTypeExpression(final TypeMirror typeMirror) {
        final var creator = new TypeExpressionCreator();
        return typeMirror.accept(creator, null);
    }
}


class ImmutableScope extends LambdaScope {

    public ImmutableScope(final LambdaScope parentScope,
                          final Tree owner) {
        super(parentScope, owner, parentScope.getLambdaContext());
    }

    @Override
    public Set<String> locals() {
        return Set.of();
    }

    @Override
    public void define(final Element element) {
    }

    @Override
    public Element resolve(final String name) {
        return null;
    }

    @Override
    public LambdaScope childScope(final Tree owner) {
        return owner != getOwner()
                ? new ImmutableScope(this, owner)
                : this;
    }
}
package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.scope.FunctionScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;

public class SymbolCreator extends AbstractTreeVisitor<Object, Scope> {

    public VariableSymbol createSymbol(final VariableDeclaratorTree variableDeclaratorTree) {
        final var scope = new GlobalScope(
                null,
                null
        );

        visitVariableDeclaratorStatement(variableDeclaratorTree, scope);
        return (VariableSymbol) variableDeclaratorTree.getName().getSymbol();
    }

    public MethodSymbol createMethod(final Function function) {
        final var returnType = function.getReturnType().getType();
        final ElementKind elementKind = ElementKind.valueOf(function.getKind().name());

        final var functionScope = new FunctionScope(
                null,
                null
        );

        final var parameters = function.getParameters().stream()
                .map(parameter -> {
                    parameter.accept(this, functionScope);
                    return (VariableSymbol) parameter.getName().getSymbol();
                })
                .toList();

        function.getBody().accept(this, functionScope);

        return new MethodSymbolBuilderImpl()
                .kind(elementKind)
                .simpleName(function.getSimpleName())
                .flags(function.getModifiers().getFlags())
                .returnType(returnType)
                .parameters(parameters)
                .build();
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                   final Scope scope) {
        final var symbol = new VariableSymbolBuilderImpl()
                .kind(ElementKind.valueOf(variableDeclaratorStatement.getKind().name()))
                .simpleName(variableDeclaratorStatement.getName().getName())
                .type(variableDeclaratorStatement.getType().getType())
                .flags(variableDeclaratorStatement.getFlags())
                .build();

        variableDeclaratorStatement.getName()
                .setSymbol(symbol);

        scope.define(symbol);

        return null;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier, final Scope scope) {
        final var symbol = scope.resolve(identifier.getName());

        if (symbol != null) {
            identifier.setSymbol(symbol);
        }

        return null;
    }

}

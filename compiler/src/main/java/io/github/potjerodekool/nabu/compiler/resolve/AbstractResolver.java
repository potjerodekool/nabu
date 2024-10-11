package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.CVariableDeclaratorStatement;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableVariableType;

public abstract class AbstractResolver extends AbstractTreeVisitor<Object, Scope> {

    protected final CompilerContext compilerContext;

    protected AbstractResolver(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
    }

    @Override
    public Object visitVariableType(final CVariableType variableType, final Scope scope) {
        final var types = compilerContext.getClassElementLoader().getTypes();
        variableType.setType(types.getVarType());
        return null;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final CVariableDeclaratorStatement variableDeclaratorStatement,
                                                   final Scope scope) {
        final var result = super.visitVariableDeclaratorStatement(variableDeclaratorStatement, scope);

        final var type = variableDeclaratorStatement.getType().getType();
        final var identifier = variableDeclaratorStatement.getIdent();

        final var varElement = new VariableElement(ElementKind.VARIABLE, identifier.getName(), null);
        varElement.setVariableType(type);

        if (type instanceof MutableVariableType variableType) {
            final var interferedType = resolveType(variableDeclaratorStatement.getValue());
            variableType.setInterferedType(interferedType);
        }

        scope.define(varElement);

        identifier.setSymbol(varElement);

        return result;
    }

    @Override
    public Object visitAsExpression(final AsExpression asExpression,
                                    final Scope scope) {
        asExpression.getExpression().accept(this, scope);
        asExpression.getTargetType().accept(this, scope);
        return asExpression;
    }

    protected TypeMirror resolveType(final Tree tree) {
        return switch (tree) {
            case CFieldAccessExpression fieldAccessExpression -> resolveType(fieldAccessExpression.getField());
            case CIdent ident -> {
                if (ident.getType() != null) {
                    yield ident.getType();
                } else {
                    yield resolveType(ident.getSymbol());
                }
            }
            case AsExpression asExpression -> resolveType(asExpression.getTargetType());
            case MethodInvocation methodInvocation -> methodInvocation.getMethodType().getReturnType();
            case CVariableType variableType -> variableType.getType();
            default -> throw new TodoException(tree.getClass().getName());
        };
    }

    protected TypeMirror resolveType(final Element element) {
        if (element instanceof VariableElement variableElement) {
            return variableElement.getVariableType();
        }

        throw new TodoException(element.getClass().getName());
    }

    @Override
    public Object visitNoTypeExpression(final CNoTypeExpression noTypeExpression,
                                        final Scope scope) {
        final var voidType = compilerContext.getClassElementLoader().getTypes()
                .getVoidType();
        noTypeExpression.setType(voidType);
        return noTypeExpression;
    }

    @Override
    public Object visitIdentifier(final CIdent ident,
                                  final Scope scope) {
        final var symbol = scope.resolve(ident.getName());

        if (symbol != null) {
            ident.setSymbol(symbol);
            return ident;
        } else {
            final var resolver = new Resolver(
                    compilerContext.getClassElementLoader(),
                    scope.getCompilationUnit().getImportScope()
            );
            final var type = resolver.resolveType(ident.getName());
            ident.setType(type);
            return ident;
        }
    }
}

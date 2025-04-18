package io.github.potjerodekool.nabu.compiler.ast.symbol;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementVisitor;
import io.github.potjerodekool.nabu.compiler.ast.element.NestingKind;
import io.github.potjerodekool.nabu.compiler.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.compiler.type.impl.CErrorType;

import java.util.List;

public class ErrorSymbol extends ClassSymbol {

    private static final WritableScope members = new WritableScope();

    public ErrorSymbol(final String name) {
        super(
                ElementKind.OTHER,
                NestingKind.TOP_LEVEL,
                0,
                name,
                new CErrorType(null),
                null,
                List.of(),
                List.of()
        );
        asType().setElement(this);
    }

    @Override
    public <R, P> R accept(final SymbolVisitor<R, P> v, final P p) {
        return null;
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return null;
    }

    @Override
    public boolean exists() {
        return false;
    }

    @Override
    public boolean isError() {
        return true;
    }

    @Override
    public WritableScope getMembers() {
        return members;
    }
}

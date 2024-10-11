package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.MethodSymbol;

import java.util.Objects;

public class FunctionScope extends AbstractScope {

    private final MethodSymbol owner;

    public FunctionScope(final Scope parent,
                         final MethodSymbol owner) {
        super(parent);
        Objects.requireNonNull(parent);
        this.owner = owner;
    }

    @Override
    public Element resolve(final String name) {
        if ("this".equals(name)) {
            return !owner.isStatic()
                    ? owner.getEnclosingElement()
                    : null;
        }

        return super.resolve(name);
    }

    @Override
    public MethodSymbol getCurrentMethod() {
        return owner;
    }
}

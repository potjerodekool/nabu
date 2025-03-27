package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;

public class FunctionScope extends AbstractScope {

    private final ExecutableElement owner;

    public FunctionScope(final Scope parent,
                         final ExecutableElement owner) {
        super(parent);
        this.owner = owner;
    }

    @Override
    public ExecutableElement getCurrentMethod() {
        return owner;
    }

    @Override
    public Element getCurrentElement() {
        return owner;
    }
}

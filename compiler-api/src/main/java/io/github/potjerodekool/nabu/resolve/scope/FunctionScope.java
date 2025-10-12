package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;

public class FunctionScope extends AbstractScope {

    private final ExecutableElement owner;

    public FunctionScope(final Scope parent,
                         final ExecutableElement owner) {
        super(parent);
        this.owner = owner;

        if (owner != null) {
            owner.getParameters().forEach(this::define);
        }
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

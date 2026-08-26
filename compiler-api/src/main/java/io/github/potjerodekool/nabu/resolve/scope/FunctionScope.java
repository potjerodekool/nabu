package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.TypeMirror;

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

    @Override
    public TypeMirror resolveType(final String name) {
        final var methodType = (ExecutableType) owner.asType();
        return methodType.getTypeVariables().stream()
                .filter(it -> it.asElement().getSimpleName().equals(name))
                .map(it -> (TypeMirror) it)
                .findFirst()
                .orElseGet(() -> super.resolveType(name));
    }
}

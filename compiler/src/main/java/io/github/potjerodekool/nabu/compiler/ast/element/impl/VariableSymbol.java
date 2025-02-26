package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;

import java.util.Set;

public class VariableSymbol extends AbstractSymbol implements VariableElement {

    public VariableSymbol(final ElementKind kind,
                          final Set<Modifier> modifiers,
                          final String name,
                          final Element owner) {
        super(kind, modifiers, name, owner);
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        throw new TodoException();
    }

    @Override
    public Object getConstantValue() {
        return null;
    }

    @Override
    public boolean isUnnamed() {
        return false;
    }
}

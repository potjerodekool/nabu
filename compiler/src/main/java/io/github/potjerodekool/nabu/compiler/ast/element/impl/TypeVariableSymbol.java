package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;

import java.util.List;
import java.util.Set;

public class TypeVariableSymbol extends TypeSymbol implements TypeParameterElement {

    public TypeVariableSymbol(final String name,
                              final Element owner,
                              final TypeVariable type) {
        super(ElementKind.TYPE_PARAMETER, Set.of(), name, owner);
        setType(type);
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj instanceof TypeParameterElement other) {
            return getSimpleName().equals(other.getSimpleName());
        }

        return false;
    }

    @Override
    public Element getGenericElement() {
        throw new TodoException();
    }

    @Override
    public List<? extends TypeMirror> getBounds() {
        throw new TodoException();
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitTypeParameterElement(this, p);
    }
}

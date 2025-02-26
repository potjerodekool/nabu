package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.ast.element.impl.AbstractSymbol;

import java.util.Set;

public abstract class TypeSymbol extends AbstractSymbol {

    public TypeSymbol(final ElementKind kind,
                      final Set<Modifier> modifiers,
                      final String name,
                      final Element owner) {
        super(kind, modifiers, name, owner);
    }

}

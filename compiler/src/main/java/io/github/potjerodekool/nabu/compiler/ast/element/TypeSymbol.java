package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.ast.element.impl.Symbol;

public abstract class TypeSymbol extends Symbol {

    public TypeSymbol(final ElementKind kind,
                      final long flags,
                      final String name,
                      final Element owner) {
        super(kind, flags, name, owner);
    }

}

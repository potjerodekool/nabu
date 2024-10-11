package io.github.potjerodekool.nabu.compiler.ast.element;

public abstract class TypeSymbol extends AbstractSymbol {

    public TypeSymbol(final ElementKind kind,
                      final String name,
                      final AbstractSymbol owner) {
        super(kind, name, owner);
    }

    public TypeSymbol(final ElementKind kind,
                      final NestingKind nestingKind,
                      final String name,
                      final AbstractSymbol owner) {
        super(kind, nestingKind, name, owner);
    }
}

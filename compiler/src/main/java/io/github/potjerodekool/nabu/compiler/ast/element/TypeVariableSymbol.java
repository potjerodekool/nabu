package io.github.potjerodekool.nabu.compiler.ast.element;

public class TypeVariableSymbol extends TypeSymbol {

    public TypeVariableSymbol(final ElementKind kind,
                              final String name,
                              final AbstractSymbol owner) {
        super(kind, name, owner);
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj instanceof TypeVariableSymbol other) {
            return getSimpleName().equals(other.getSimpleName());
        }

        return false;
    }
}

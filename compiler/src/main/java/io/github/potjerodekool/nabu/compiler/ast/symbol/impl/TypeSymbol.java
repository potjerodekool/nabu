package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;

public abstract class TypeSymbol extends Symbol {

    public TypeSymbol(final ElementKind kind,
                      final long flags,
                      final String name,
                      final AbstractType type,
                      final Symbol owner) {
        super(kind, flags, name, type, owner);

        if (type != null) {
            type.setElement(this);
        }
    }

    @Override
    public List<Symbol> getEnclosedElements() {
        complete();

        final var members = getMembers();

        if (members == null) {
            return List.of();
        }

        return members.elements().stream()
                .map(it -> (Symbol) it)
                .filter(symbol -> {
                            final var notSynthetic = !Flags.hasFlag(symbol.getFlags(), Flags.SYNTHETIC);
                            final var isEnclosed = symbol.getEnclosingElement() == this;
                            final var notError = !symbol.isError();

                            return notSynthetic
                                    && isEnclosed
                                    && notError;
                        }
                )
                .toList();
    }

    public static String createFullName(final Symbol owner,
                                        final String name) {
        final String fullName;
        if (owner == null) {
            fullName = name;
        } else if (owner.isError()) {
            fullName = name;
        } else {
            fullName =  owner.getQualifiedName() + "." + name;
        }

        return fullName;
    }

    protected TypeMirror getErasureField() {
        return null;
    }
}

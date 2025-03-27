package io.github.potjerodekool.nabu.compiler.ast.symbol;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;

import java.util.List;

public abstract class TypeSymbol extends Symbol {

    public TypeSymbol(final ElementKind kind,
                      final long flags,
                      final String name,
                      final AbstractType type,
                      final Symbol owner) {
        super(kind, flags, name, type, owner);
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
                .peek(Symbol::complete)
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
        if (owner == null) {
            return name;
        } else if (owner.isError()) {
            return name;
        } else {
            return owner.getQualifiedName() + "." + name;
        }
    }

}

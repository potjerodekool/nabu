package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

public class Substitute extends StructuralTypeMapping<Void> {

    private List<? extends TypeMirror> from;
    private List<? extends TypeMirror> to;

    public Substitute() {
        this.from = new ArrayList<>();
        this.to = new ArrayList<>();
    }

    public Substitute(final List<? extends TypeMirror> from,
                      final List<? extends TypeMirror> to) {
        if (from.size() > to.size()) {
            final var fromIndex = from.size() - to.size();
            this.from = from.subList(fromIndex, from.size());
        } else {
            this.from = from;
        }

        if (this.from.size() < to.size()) {
            final var toIndex = to.size() - from.size();
            this.to = to.subList(toIndex, to.size());
        }
    }


    public TypeMirror substitute(final TypeMirror typeMirror,
                                 final List<? extends TypeMirror> from,
                                 final List<? extends TypeMirror> to) {
        return null;
    }
}

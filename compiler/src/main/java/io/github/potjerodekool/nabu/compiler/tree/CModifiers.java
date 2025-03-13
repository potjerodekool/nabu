package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;

import java.util.ArrayList;
import java.util.List;

public class CModifiers {

    private final List<AnnotationTree> annotations = new ArrayList<>();
    private final long flags;

    public CModifiers() {
        this.flags = 0;
    }

    public CModifiers(final List<? extends AnnotationTree> annotations,
                      final long flags) {
        this.annotations.addAll(annotations);
        this.flags = flags;
    }

    public CModifiers with(final int flag) {
        final var newFlags = flags + flag;

        return new CModifiers(
                annotations,
                newFlags
        );
    }

    public List<AnnotationTree> getAnnotations() {
        return annotations;
    }

    public long getFlags() {
        return flags;
    }

    public boolean hasFlag(final int flag) {
        return (flags & flag) != 0;
    }

    public boolean hasAccessModifier() {
        return hasFlag(Flags.PUBLIC)
                || hasFlag(Flags.PROTECTED)
                || hasFlag(Flags.PRIVATE);
    }
}

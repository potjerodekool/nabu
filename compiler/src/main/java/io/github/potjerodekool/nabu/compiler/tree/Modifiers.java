package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.ast.Flags;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;

import java.util.ArrayList;
import java.util.List;

public class Modifiers {

    private final List<AnnotationTree> annotations = new ArrayList<>();
    private final long flags;

    public Modifiers() {
        this.flags = 0;
    }

    public Modifiers(final long flags) {
        this(List.of(), flags);
    }

    public Modifiers(final List<? extends AnnotationTree> annotations,
                     final long flags) {
        this.annotations.addAll(annotations);
        this.flags = flags;
    }

    public Modifiers with(final long flag) {
        final var newFlags = flags + flag;

        return new Modifiers(
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

    public boolean hasFlag(final long flag) {
        return (flags & flag) != 0;
    }

    public boolean hasAccessModifier() {
        return hasFlag(Flags.PUBLIC)
                || hasFlag(Flags.PROTECTED)
                || hasFlag(Flags.PRIVATE);
    }
}

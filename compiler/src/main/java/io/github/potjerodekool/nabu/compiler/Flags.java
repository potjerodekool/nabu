package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.ast.element.Modifier;

import java.util.Set;

public class Flags {

    public static final int PUBLIC = 1;
    public static final int PRIVATE = 2;
    public static final int STATIC = 4;
    public static final int SYNTHENTIC = 8;
    public static final int FINAL = 16;

    private Flags() {
    }

    public static int parse(final Set<Modifier> modifiers) {
        int flags = 0;

        if (modifiers.contains(Modifier.PUBLIC)) {
            flags += PUBLIC;
        }

        if (modifiers.contains(Modifier.PRIVATE)) {
            flags += PRIVATE;
        }

        if (modifiers.contains(Modifier.STATIC)) {
            flags += STATIC;
        }

        if (modifiers.contains(Modifier.SYNTHENTIC)) {
            flags += SYNTHENTIC;
        }

        if (modifiers.contains(Modifier.FINAL)) {
            flags += FINAL;
        }

        return flags;
    }

}

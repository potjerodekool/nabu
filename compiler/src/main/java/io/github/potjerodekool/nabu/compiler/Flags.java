package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.ast.element.Modifier;

import java.util.*;
import java.util.stream.Collectors;

public class Flags {

    public static final int ABSTRACT = 1;
    public static final int PUBLIC = 1 << 1;
    public static final int PRIVATE = 1 << 2;
    public static final int PROTECTED = 1 << 3;
    public static final int STATIC = 1 << 4;
    public static final int FINAL = 1 << 5;
    public static final int SYNTHETIC = 1 << 6;
    public static final int VOLATILE = 1 << 7;
    public static final int TRANSIENT = 1 << 8;
    public static final int NATIVE = 1 << 9;
    public static final int INTERFACE = 1 << 10;
    public static final int STRICTFP = 1 << 11;


    private Flags() {
    }

    public static long parse(final Collection<Modifier> modifiers) {
        long flags = 0;

        if (modifiers.contains(Modifier.PUBLIC)) {
            flags += PUBLIC;
        }

        if (modifiers.contains(Modifier.PRIVATE)) {
            flags += PRIVATE;
        }

        if (modifiers.contains(Modifier.STATIC)) {
            flags += STATIC;
        }

        if (modifiers.contains(Modifier.SYNTHETIC)) {
            flags += SYNTHETIC;
        }

        if (modifiers.contains(Modifier.FINAL)) {
            flags += FINAL;
        }

        return flags;
    }

    public static Set<Modifier> createModifiers(final long flags) {
        return Arrays.stream(Modifier.values())
                .filter(modifier -> (flags & modifier.getFlag()) != 0)
                .collect(Collectors.toSet());
    }

    public static boolean hasFlag(final long flags,
                                  final int flag) {
        return (flags & flag) != 0;
    }
}
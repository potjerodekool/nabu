package io.github.potjerodekool.nabu.lang;

import io.github.potjerodekool.nabu.lang.model.element.Modifier;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Flags to use on elements.
 */
public class Flags {

    public static final long ABSTRACT = 1;
    public static final long PUBLIC = 1 << 1;
    public static final long PRIVATE = 1 << 2;
    public static final long PROTECTED = 1 << 3;
    public static final long STATIC = 1 << 4;
    public static final long FINAL = 1 << 5;
    public static final long SYNTHETIC = 1 << 6;
    public static final long VOLATILE = 1 << 7;
    public static final long TRANSIENT = 1 << 8;
    public static final long NATIVE = 1 << 9;
    public static final long INTERFACE = 1 << 10;
    public static final long STRICTFP = 1 << 11;
    public static final long DEFAULT = 1 << 12;
    public static final long SUPER = 1 << 13;
    public static final long ANNOTATION = 1 << 14;
    public static final long ENUM = 1 << 15;
    public static final long MODULE = 1 << 16;
    public static final long RECORD = 1 << 17;
    public static final long DEPRECATED = 1 << 18;
    public static final long MANDATED = 1 << 19;
    public static final long SYNCHRONIZED = 1 << 20;
    public static final long BRIDGE = 1 << 21;
    public static final long VARARGS = 1 << 22;
    public static final long STRICT = 1 << 23;
    public static final long GENERATED_DEFAULT_CONSTRUCTOR = 1 << 24;
    public static final long EXISTS = 1 << 25;
    public static final long AUTOMATIC_MODULE = 1 << 26;
    public static final long TRANSITIVE = 1 << 27;
    public static final long COMPACT_RECORD_CONSTRUCTOR = 1 << 28;

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
                                  final long flag) {
        return (flags & flag) != 0;
    }

    public static boolean hasAccessModifier(final long flags) {
        return hasFlag(flags, Flags.PUBLIC)
                || hasFlag(flags, Flags.PRIVATE)
                || hasFlag(flags, Flags.PROTECTED);
    }
}

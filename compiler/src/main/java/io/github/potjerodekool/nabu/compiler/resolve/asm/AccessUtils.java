package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.Modifier;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

public final class AccessUtils {

    private AccessUtils() {
    }

    public static Set<Modifier> parseModifiers(final int access) {
        final var modifiers = new HashSet<Modifier>();

        if (hasOpcode(access, Opcodes.ACC_PUBLIC)) {
            modifiers.add(Modifier.PUBLIC);
        }

        if (hasOpcode(access, Opcodes.ACC_PRIVATE)) {
            modifiers.add(Modifier.PRIVATE);
        }

        if (hasOpcode(access, Opcodes.ACC_PROTECTED)) {
            modifiers.add(Modifier.PROTECTED);
        }

        if (hasOpcode(access, Opcodes.ACC_FINAL)) {
            modifiers.add(Modifier.FINAL);
        }

        if (hasOpcode(access, Opcodes.ACC_STATIC)) {
            modifiers.add(Modifier.STATIC);
        }

        if (hasOpcode(access, Opcodes.ACC_TRANSIENT)) {
            modifiers.add(Modifier.TRANSIENT);
        }

        if (hasOpcode(access, Opcodes.ACC_VOLATILE)) {
            modifiers.add(Modifier.VOLATILE);
        }

        return modifiers;
    }

    public static long parseClassAccessToFlags(final int access) {
        long flags = 0;

        if (hasOpcode(access, Opcodes.ACC_PUBLIC)) {
            flags += Flags.PUBLIC;
        }

        if (hasOpcode(access, Opcodes.ACC_PRIVATE)) {
            flags += Flags.PRIVATE;
        }

        if (hasOpcode(access, Opcodes.ACC_PROTECTED)) {
            flags += Flags.PROTECTED;
        }

        if (hasOpcode(access, Opcodes.ACC_FINAL)) {
            flags += Flags.FINAL;
        }

        if (hasOpcode(access, Opcodes.ACC_SUPER)) {
            flags += Flags.SUPER;
        }

        if (hasOpcode(access, Opcodes.ACC_INTERFACE)) {
            flags += Flags.INTERFACE;
        }

        if (hasOpcode(access, Opcodes.ACC_ABSTRACT)) {
            flags += Flags.ABSTRACT;
        }

        if (hasOpcode(access, Opcodes.ACC_SYNTHETIC)) {
            flags += Flags.SYNTHETIC;
        }

        if (hasOpcode(access, Opcodes.ACC_ANNOTATION)) {
            flags += Flags.ANNOTATION;
        }

        if (hasOpcode(access, Opcodes.ACC_ENUM)) {
            flags += Flags.ENUM;
        }

        if (hasOpcode(access, Opcodes.ACC_MODULE)) {
            flags += Flags.MODULE;
        }

        if (hasOpcode(access, Opcodes.ACC_RECORD)) {
            flags += Flags.RECORD;
        }

        if (hasOpcode(access, Opcodes.ACC_DEPRECATED)) {
            flags += Flags.DEPRECATED;
        }

        return flags;
    }

    public static long parseFieldAccessToFlags(final int access) {
        long flags = 0;

        if (hasOpcode(access, Opcodes.ACC_PUBLIC)) {
            flags += Flags.PUBLIC;
        }

        if (hasOpcode(access, Opcodes.ACC_PRIVATE)) {
            flags += Flags.PRIVATE;
        }

        if (hasOpcode(access, Opcodes.ACC_PROTECTED)) {
            flags += Flags.PROTECTED;
        }

        if (hasOpcode(access, Opcodes.ACC_STATIC)) {
            flags += Flags.STATIC;
        }

        if (hasOpcode(access, Opcodes.ACC_FINAL)) {
            flags += Flags.FINAL;
        }

        if (hasOpcode(access, Opcodes.ACC_VOLATILE)) {
            flags += Flags.VOLATILE;
        }

        if (hasOpcode(access, Opcodes.ACC_TRANSIENT)) {
            flags += Flags.TRANSIENT;
        }

        if (hasOpcode(access, Opcodes.ACC_SYNTHETIC)) {
            flags += Flags.SYNTHETIC;
        }

        if (hasOpcode(access, Opcodes.ACC_ENUM)) {
            flags += Flags.ENUM;
        }

        if (hasOpcode(access, Opcodes.ACC_MANDATED)) {
            flags += Flags.MANDATED;
        }

        return flags;
    }

    public static long parseMethodAccessToFlags(final int access) {
        long flags = 0;

        if (hasOpcode(access, Opcodes.ACC_PUBLIC)) {
            flags += Flags.PUBLIC;
        }

        if (hasOpcode(access, Opcodes.ACC_PRIVATE)) {
            flags += Flags.PRIVATE;
        }

        if (hasOpcode(access, Opcodes.ACC_PROTECTED)) {
            flags += Flags.PROTECTED;
        }

        if (hasOpcode(access, Opcodes.ACC_STATIC)) {
            flags += Flags.STATIC;
        }

        if (hasOpcode(access, Opcodes.ACC_FINAL)) {
            flags += Flags.FINAL;
        }

        if (hasOpcode(access, Opcodes.ACC_SYNCHRONIZED)) {
            flags += Flags.SYNCHRONIZED;
        }

        if (hasOpcode(access, Opcodes.ACC_BRIDGE)) {
            flags += Flags.BRIDGE;
        }

        if (hasOpcode(access, Opcodes.ACC_VARARGS)) {
            flags += Flags.VARARGS;
        }

        if (hasOpcode(access, Opcodes.ACC_NATIVE)) {
            flags += Flags.NATIVE;
        }

        if (hasOpcode(access, Opcodes.ACC_ABSTRACT)) {
            flags += Flags.ABSTRACT;
        }

        if (hasOpcode(access, Opcodes.ACC_STRICT)) {
            flags += Flags.STRICT;
        }

        if (hasOpcode(access, Opcodes.ACC_SYNTHETIC)) {
            flags += Flags.SYNTHETIC;
        }

        if (hasOpcode(access, Opcodes.ACC_MANDATED)) {
            flags += Flags.MANDATED;
        }

        return flags;
    }

    public static long toFlags(final int access) {
        long flags = 0;

        if (hasOpcode(access, Opcodes.ACC_PUBLIC)) {
            flags += Flags.PUBLIC;
        }

        if (hasOpcode(access, Opcodes.ACC_PRIVATE)) {
            flags += Flags.PRIVATE;
        }

        if (hasOpcode(access, Opcodes.ACC_PROTECTED)) {
            flags += Flags.PROTECTED;
        }

        if (hasOpcode(access, Opcodes.ACC_FINAL)) {
            flags += Flags.FINAL;
        }

        if (hasOpcode(access, Opcodes.ACC_STATIC)) {
            flags += Flags.STATIC;
        }

        if (hasOpcode(access, Opcodes.ACC_TRANSIENT)) {
            flags += Flags.TRANSIENT;
        }

        if (hasOpcode(access, Opcodes.ACC_VOLATILE)) {
            flags += Flags.VOLATILE;
        }

        return flags;
    }

    public static boolean hasOpcode(final int access,
                                    final int opcode) {
        return (access & opcode) == opcode;
    }

    public static int flagsToAccess(final long flags) {
        int access = 0;

        if (Flags.hasFlag(flags, Flags.ABSTRACT)) {
            access += Opcodes.ACC_ABSTRACT;
        }
        if (Flags.hasFlag(flags, Flags.PUBLIC)) {
            access += Opcodes.ACC_PUBLIC;
        }
        if (Flags.hasFlag(flags, Flags.PRIVATE)) {
            access += Opcodes.ACC_PRIVATE;
        }
        if (Flags.hasFlag(flags, Flags.PROTECTED)) {
            access += Opcodes.ACC_PROTECTED;
        }
        if (Flags.hasFlag(flags, Flags.STATIC)) {
            access += Opcodes.ACC_STATIC;
        }
        if (Flags.hasFlag(flags, Flags.FINAL)) {
            access += Opcodes.ACC_FINAL;
        }
        if (Flags.hasFlag(flags, Flags.SYNTHETIC)) {
            access += Opcodes.ACC_SYNTHETIC;
        }
        if (Flags.hasFlag(flags, Flags.VOLATILE)) {
            access += Opcodes.ACC_VOLATILE;
        }
        if (Flags.hasFlag(flags, Flags.TRANSIENT)) {
            access += Opcodes.ACC_TRANSIENT;
        }
        if (Flags.hasFlag(flags, Flags.NATIVE)) {
            access += Opcodes.ACC_NATIVE;
        }
        if (Flags.hasFlag(flags, Flags.INTERFACE)) {
            access += Opcodes.ACC_INTERFACE;
        }
        if (Flags.hasFlag(flags, Flags.STRICTFP)) {
            access += Opcodes.ACC_STRICT;
        }

        if (Flags.hasFlag(flags, Flags.SUPER)) {
            access += Opcodes.ACC_SUPER;
        }
        if (Flags.hasFlag(flags, Flags.ANNOTATION)) {
            access += Opcodes.ACC_ANNOTATION;
        }
        if (Flags.hasFlag(flags, Flags.ENUM)) {
            access += Opcodes.ACC_ENUM;
        }
        if (Flags.hasFlag(flags, Flags.MODULE)) {
            access += Opcodes.ACC_MODULE;
        }
        if (Flags.hasFlag(flags, Flags.RECORD)) {
            access += Opcodes.ACC_RECORD;
        }
        if (Flags.hasFlag(flags, Flags.DEPRECATED)) {
            access += Opcodes.ACC_DEPRECATED;
        }
        if (Flags.hasFlag(flags, Flags.MANDATED)) {
            access += Opcodes.ACC_MANDATED;
        }
        if (Flags.hasFlag(flags, Flags.SYNCHRONIZED)) {
            access += Opcodes.ACC_SYNCHRONIZED;
        }
        if (Flags.hasFlag(flags, Flags.BRIDGE)) {
            access += Opcodes.ACC_BRIDGE;
        }
        if (Flags.hasFlag(flags, Flags.VARARGS)) {
            access += Opcodes.ACC_VARARGS;
        }
        if (Flags.hasFlag(flags, Flags.STRICT)) {
            access += Opcodes.ACC_STRICT;
        }

        return access;
    }
}

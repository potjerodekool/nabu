package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.lang.Flags;
import org.objectweb.asm.Opcodes;

/**
 * Utilities to convert access to flags and vice versa.
 */
public final class AccessUtils {

    private AccessUtils() {
    }

    /**
     * Converts access to flags.
     *
     * @param access Access value
     * @return Return flags
     */
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

    /**
     * Converts field access to flags.
     *
     * @param access Access value
     * @return Returns flags
     */
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

    /**
     * Converts method access to flags.
     *
     * @param access Access value
     * @return Return flags
     */
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


    private static boolean hasOpcode(final int access,
                                     final int opcode) {
        return (access & opcode) == opcode;
    }

    /**
     * Converts flags to access.
     * @param flags Flags value
     * @return Returns the access value
     */
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

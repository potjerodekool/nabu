package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.Modifier;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

public final class Modifiers {

    private Modifiers() {
    }

    public static Set<Modifier> parse(final int access) {
        final var modifiers = new HashSet<Modifier>();

        if (hasFlag(access, Opcodes.ACC_PUBLIC)) {
            modifiers.add(Modifier.PUBLIC);
        }

        if (hasFlag(access, Opcodes.ACC_PRIVATE)) {
            modifiers.add(Modifier.PRIVATE);
        }

        if (hasFlag(access, Opcodes.ACC_PROTECTED)) {
            modifiers.add(Modifier.PROTECTED);
        }

        if (hasFlag(access, Opcodes.ACC_FINAL)) {
            modifiers.add(Modifier.FINAL);
        }

        if (hasFlag(access, Opcodes.ACC_STATIC)) {
            modifiers.add(Modifier.STATIC);
        }

        if (hasFlag(access, Opcodes.ACC_TRANSIENT)) {
            modifiers.add(Modifier.TRANSIENT);
        }

        if (hasFlag(access, Opcodes.ACC_VOLATILE)) {
            modifiers.add(Modifier.VOLATILE);
        }

        return modifiers;
    }

    private static boolean hasFlag(final int access,
                            final int opcode) {
        return (access & opcode) == opcode;
    }
}

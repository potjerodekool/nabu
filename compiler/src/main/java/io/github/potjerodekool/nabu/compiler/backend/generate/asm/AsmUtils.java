package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.Modifier;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.Set;

public final class AsmUtils {

    private static final Map<Modifier, Integer> modifierToAccessMap = Map.of(
            Modifier.PUBLIC, Opcodes.ACC_PUBLIC,
            Modifier.PRIVATE, Opcodes.ACC_PRIVATE,
            Modifier.PROTECTED, Opcodes.ACC_PROTECTED,
            Modifier.STATIC, Opcodes.ACC_STATIC,
            Modifier.SYNTHETIC, Opcodes.ACC_SYNTHETIC,
            Modifier.FINAL, Opcodes.ACC_FINAL,
            Modifier.TRANSIENT, Opcodes.ACC_TRANSIENT,
            Modifier.VOLATILE, Opcodes.ACC_VOLATILE
    );

    private AsmUtils() {
    }

    public static int calculateAccess(final Set<Modifier> modifiers) {
        return modifiers.stream()
                .mapToInt(modifierToAccessMap::get)
                .reduce(Integer::sum)
                .orElse(0);
    }

    public static int calculateAccess(final int flags) {
        var access = addFlag(flags, Flags.PUBLIC, Opcodes.ACC_PUBLIC);
        access += addFlag(flags, Flags.PRIVATE, Opcodes.ACC_PRIVATE);
        access += addFlag(flags, Flags.STATIC, Opcodes.ACC_STATIC);
        access += addFlag(flags, Flags.SYNTHETIC, Opcodes.ACC_SYNTHETIC);
        return access;
    }

    private static int addFlag(final int flags,
                        final int flag,
                        final int value) {
        if ((flags & flag) == flag) {
            return value;
        } else {
            return 0;
        }
    }
}

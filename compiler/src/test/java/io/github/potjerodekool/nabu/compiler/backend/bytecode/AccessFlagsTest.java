package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A volatile field of a JDK class has to stay volatile: the backend makes its accesses sequentially consistent. The
 * bit of volatile is that of bridge for a method, so the flags of a field are mapped on their own.
 */
class AccessFlagsTest {

    @Test
    void volatileFieldKeepsItsFlag() {
        final long flags = AccessFlags.toNabuFieldFlags(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE);
        assertTrue(Flags.hasFlag(flags, Flags.VOLATILE));
        assertTrue(Flags.hasFlag(flags, Flags.PRIVATE));
        assertFalse(Flags.hasFlag(flags, Flags.BRIDGE), "the bit of volatile is not a bridge for a field");
    }

    @Test
    void transientFieldIsNoVarargs() {
        final long flags = AccessFlags.toNabuFieldFlags(Opcodes.ACC_TRANSIENT);
        assertTrue(Flags.hasFlag(flags, Flags.TRANSIENT));
        assertFalse(Flags.hasFlag(flags, Flags.VARARGS));
        assertFalse(Flags.hasFlag(flags, Flags.VOLATILE));
    }

    @Test
    void methodStillGetsBridge() {
        final long flags = AccessFlags.toNabuFlags(Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC);
        assertTrue(Flags.hasFlag(flags, Flags.BRIDGE));
        assertFalse(Flags.hasFlag(flags, Flags.VOLATILE));
    }
}

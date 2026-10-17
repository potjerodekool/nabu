package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.lang.Flags;
import org.objectweb.asm.Opcodes;

/**
 * Vertaalt JVM-classfile access-flags (de bitlayout van
 * {@link org.objectweb.asm.Opcodes}{@code .ACC_*}, zoals gelezen uit een
 * {@code .class}-bestand) naar Nabu's eigen {@link Flags}-bitmasker, die een
 * ANDERE bitlayout gebruikt (zie {@code Flags.java}: {@code ABSTRACT=1},
 * {@code PUBLIC=1<<1}, enz. - niet de JVM-volgorde).
 */
final class AccessFlags {

    private AccessFlags() {
    }

    static long toNabuFlags(final int asmAccess) {
        long flags = 0L;
        if (has(asmAccess, Opcodes.ACC_PUBLIC)) flags |= Flags.PUBLIC;
        if (has(asmAccess, Opcodes.ACC_PRIVATE)) flags |= Flags.PRIVATE;
        if (has(asmAccess, Opcodes.ACC_PROTECTED)) flags |= Flags.PROTECTED;
        if (has(asmAccess, Opcodes.ACC_STATIC)) flags |= Flags.STATIC;
        if (has(asmAccess, Opcodes.ACC_FINAL)) flags |= Flags.FINAL;
        if (has(asmAccess, Opcodes.ACC_SYNTHETIC)) flags |= Flags.SYNTHETIC;
        if (has(asmAccess, Opcodes.ACC_NATIVE)) flags |= Flags.NATIVE;
        if (has(asmAccess, Opcodes.ACC_ABSTRACT)) flags |= Flags.ABSTRACT;
        if (has(asmAccess, Opcodes.ACC_SYNCHRONIZED)) flags |= Flags.SYNCHRONIZED;
        if (has(asmAccess, Opcodes.ACC_VARARGS)) flags |= Flags.VARARGS;
        if (has(asmAccess, Opcodes.ACC_BRIDGE)) flags |= Flags.BRIDGE;
        if (has(asmAccess, Opcodes.ACC_STRICT)) flags |= Flags.STRICT;
        if (has(asmAccess, Opcodes.ACC_INTERFACE)) flags |= Flags.INTERFACE;
        if (has(asmAccess, Opcodes.ACC_ENUM)) flags |= Flags.ENUM;
        if (has(asmAccess, Opcodes.ACC_DEPRECATED)) flags |= Flags.DEPRECATED;
        return flags;
    }

    private static boolean has(final int access, final int bit) {
        return (access & bit) != 0;
    }
}

package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.RandomSupport;

/**
 * {@code sun.security.provider.SeedGenerator} gets its seed from the operating system through the
 * runtime ({@link RandomSupport}). The JDK picks a platform generator in its static initializer,
 * which reads files, loads providers and starts threads.
 */
public final class SeedGeneratorSubstitution {

    private SeedGeneratorSubstitution() {
    }

    public static void generateSeed(final byte[] result) {
        RandomSupport.fill(result);
    }

    static byte[] getSystemEntropy() {
        final byte[] entropy = new byte[20];
        RandomSupport.fill(entropy);
        return entropy;
    }
}

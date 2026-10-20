package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.security.SecureRandomSpi;

/**
 * The generator behind {@code new SecureRandom()} in a native executable: the random bytes of the
 * operating system ({@link RandomSupport}). Seeding adds nothing to a generator that is already unpredictable.
 */
public final class OsSecureRandomSpi extends SecureRandomSpi {

    private static final long serialVersionUID = 1L;

    public OsSecureRandomSpi() {
    }

    @Override
    protected void engineSetSeed(final byte[] seed) {
    }

    @Override
    protected void engineNextBytes(final byte[] bytes) {
        RandomSupport.fill(bytes);
    }

    @Override
    protected byte[] engineGenerateSeed(final int numBytes) {
        final byte[] seed = new byte[numBytes];
        RandomSupport.fill(seed);
        return seed;
    }
}

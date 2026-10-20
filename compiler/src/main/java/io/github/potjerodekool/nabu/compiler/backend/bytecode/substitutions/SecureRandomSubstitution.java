package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.OsSecureRandomSpi;

import java.security.SecureRandomSpi;

/**
 * {@code SecureRandom.getDefaultPRNG}: the JDK looks up the first {@code SecureRandom} service of the
 * installed providers and builds it by reflection (DRBG, with a constructor that takes parameters). A native
 * executable uses the operating system's random bytes directly. The fields are declared here only to compile
 * against; their owner becomes {@code SecureRandom} when the body is copied.
 */
public final class SecureRandomSubstitution {

    private SecureRandomSpi secureRandomSpi;
    private String algorithm;

    private SecureRandomSubstitution() {
    }

    private void getDefaultPRNG(final boolean setSeed, final byte[] seed) {
        final OsSecureRandomSpi spi = new OsSecureRandomSpi();
        this.secureRandomSpi = spi;
        this.algorithm = "OS";
    }
}

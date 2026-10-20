package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/** Random bytes from the operating system, for the seed generator of {@code java.security.SecureRandom}. */
public final class RandomSupport {

    private RandomSupport() {
    }

    /** Fills the array with bytes from the operating system's random generator. */
    private static native void fill0(byte[] bytes);

    public static void fill(final byte[] bytes) {
        fill0(bytes);
    }
}

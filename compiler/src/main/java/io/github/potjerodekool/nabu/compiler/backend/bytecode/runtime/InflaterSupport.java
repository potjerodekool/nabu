package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * The native {@code Inflater.end(long)} under a name of its own: the native method and the instance method
 * {@code Inflater.end()} get the same symbol, and a C definition of that symbol took the place of the Java method
 * (which then freed the Inflater object as if it were the native state). The call is redirected here (see RuntimeCalls).
 */
public final class InflaterSupport {

    private InflaterSupport() {
    }

    /** Frees the native state made by {@code Inflater.init}. */
    public static native void end(long address);
}

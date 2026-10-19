package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * Placeholder for the resource contents of a native executable: the
 * compiler generates this class (with the same name) from the resources it
 * embeds, see {@code EmbeddedResources}. Only the signature here matters.
 */
public final class Resources {

    private Resources() {
    }

    /** The contents of the resource at {@code name} (no leading slash), or null. */
    public static byte[] data(final String name) {
        return null;
    }
}

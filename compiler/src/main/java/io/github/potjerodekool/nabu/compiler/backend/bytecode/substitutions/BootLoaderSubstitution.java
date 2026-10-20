package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * {@code BootLoader.loadLibrary}: the JDK loads native libraries ({@code zip}, {@code nio}, ...) for
 * natives that a native executable implements in its C runtime or does not have, so there is nothing to load.
 */
public final class BootLoaderSubstitution {

    private BootLoaderSubstitution() {
    }

    public static void loadLibrary(final String name) {
    }
}

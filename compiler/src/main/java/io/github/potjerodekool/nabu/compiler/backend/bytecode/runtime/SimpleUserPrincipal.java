package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.nio.file.attribute.UserPrincipal;

/** The owner of a file as {@code Files.getOwner} reports it: the name of the user running the program. */
public final class SimpleUserPrincipal implements UserPrincipal {

    private final String name;

    public SimpleUserPrincipal(final String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}

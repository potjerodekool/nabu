package io.github.potjerodekool.nabu.resolve.scope;

import java.util.Objects;

public class LocalScope extends AbstractScope {

    public LocalScope(final Scope parent) {
        super(parent);
        Objects.requireNonNull(parent);
    }
}

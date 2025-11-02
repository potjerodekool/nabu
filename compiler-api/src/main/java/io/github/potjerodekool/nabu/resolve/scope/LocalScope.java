package io.github.potjerodekool.nabu.resolve.scope;

/**
 * Local scope for use within a lambda.
 */
public class LocalScope extends AbstractScope {

    public LocalScope(final Scope parent) {
        super(parent);
    }
}

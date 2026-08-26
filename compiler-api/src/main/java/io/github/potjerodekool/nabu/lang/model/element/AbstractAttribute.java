package io.github.potjerodekool.nabu.lang.model.element;

/**
 * Abstract base class for attributes.
 */
public abstract non-sealed class AbstractAttribute implements Attribute {

    private final boolean synthesized = false;

    public AbstractAttribute() {
    }

    /**
     * @see Attribute
     */
    @Override
    public boolean isSynthesized() {
        return synthesized;
    }
}

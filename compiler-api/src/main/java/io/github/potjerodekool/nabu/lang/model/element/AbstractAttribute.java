package io.github.potjerodekool.nabu.lang.model.element;

public abstract class AbstractAttribute implements Attribute {

    private boolean synthesized = false;

    @Override
    public boolean isSynthesized() {
        return synthesized;
    }

    public void setSynthesized(final boolean synthesized) {
        this.synthesized = synthesized;
    }
}

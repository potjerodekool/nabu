package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.Attribute;

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

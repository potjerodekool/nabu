package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.Attribute;

public abstract sealed class AbstractAttribute implements Attribute
        permits CArrayAttribute, CArrayAttributeProxy, CCompoundAttribute, CConstantAttribute, CEnumAttribute, CEnumAttributeProxy {

    private boolean synthesized = false;

    @Override
    public boolean isSynthesized() {
        return synthesized;
    }

    public void setSynthesized(final boolean synthesized) {
        this.synthesized = synthesized;
    }
}

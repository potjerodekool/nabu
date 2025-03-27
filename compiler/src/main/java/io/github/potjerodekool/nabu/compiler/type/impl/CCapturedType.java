package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.type.CapturedType;
import io.github.potjerodekool.nabu.compiler.type.WildcardType;

public class CCapturedType extends CTypeVariable implements CapturedType {

    private final WildcardType wildcard;

    public CCapturedType(final String name) {
        super(name);
        this.wildcard = null;
    }

    @Override
    public WildcardType getWildcard() {
        return wildcard;
    }

    @Override
    public String getClassName() {
        return "captured";
    }
}

package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.type.CapturedType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.WildcardType;

import java.util.Objects;

public class CCapturedType extends CTypeVariable implements CapturedType {

    private final WildcardType wildcard;

    public CCapturedType(final String name,
                         final Symbol owner,
                         final TypeMirror upper,
                         final TypeMirror lower,
                         final WildcardType wildcard) {
        super(name, owner, upper, lower);
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

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof CCapturedType other
                && Objects.equals(wildcard, other.wildcard);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wildcard);
    }
}

package io.github.potjerodekool.nabu.type;

public interface CapturedType extends TypeVariable {
    WildcardType getWildcard();
}

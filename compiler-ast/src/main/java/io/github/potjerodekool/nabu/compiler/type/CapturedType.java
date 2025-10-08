package io.github.potjerodekool.nabu.compiler.type;

public interface CapturedType extends TypeVariable {
    WildcardType getWildcard();
}

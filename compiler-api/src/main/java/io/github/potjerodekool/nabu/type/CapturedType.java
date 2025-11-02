package io.github.potjerodekool.nabu.type;

/**
 * A captured type.
 */
public interface CapturedType extends TypeVariable {
    WildcardType getWildcard();
}

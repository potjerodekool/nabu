package io.github.potjerodekool.nabu.type;

/**
 * A reference type.
 */
public interface ReferenceType extends TypeMirror {

    @Override
    default boolean isReferenceType() {
        return true;
    }
}

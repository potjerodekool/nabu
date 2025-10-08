package io.github.potjerodekool.nabu.compiler.type;

public interface ReferenceType extends TypeMirror {

    @Override
    default boolean isReferenceType() {
        return true;
    }
}

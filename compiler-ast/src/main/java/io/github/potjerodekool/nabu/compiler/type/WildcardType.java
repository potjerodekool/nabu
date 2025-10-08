package io.github.potjerodekool.nabu.compiler.type;

public interface WildcardType extends TypeMirror {

    TypeMirror getExtendsBound();

    TypeMirror getSuperBound();

    TypeMirror getBound();

    BoundKind getBoundKind();

    default boolean isExtendsBound() {
        return getBoundKind() == BoundKind.EXTENDS;
    }

    default boolean isSuperBound() {
        return getBoundKind() == BoundKind.SUPER;
    }
}

package io.github.potjerodekool.nabu.compiler.type;

public interface WildcardType extends TypeMirror {

    TypeMirror getExtendsBound();

    TypeMirror getSuperBound();
}

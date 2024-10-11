package io.github.potjerodekool.nabu.compiler.type;

public interface ArrayType extends TypeMirror {
    TypeMirror getComponentType();
}

package io.github.potjerodekool.nabu.compiler.type;

public interface TypeMirror {

    TypeKind getKind();

    <R, P> R accept(TypeVisitor<R, P> visitor, P param);

}

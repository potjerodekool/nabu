package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public interface TypeElement extends Element, QualifiedNameable {

    NestingKind getNestingKind();

    List<? extends TypeParameterElement> getTypeParameters();

    ExecutableElement findFunctionalMethod();

    TypeMirror getSuperclass();

    List<? extends TypeMirror> getInterfaces();

    default List<? extends TypeMirror> getPermittedSubclasses() {
        return List.of();
    }

}

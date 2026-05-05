package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;
import java.util.Optional;

/**
 * A type element.
 */
public interface TypeElement extends Element, QualifiedNameable {

    NestingKind getNestingKind();

    List<? extends TypeParameterElement> getTypeParameters();

    boolean isFunctionalInterface();

    ExecutableElement findFunctionalMethod();

    TypeMirror getSuperclass();

    List<? extends TypeMirror> getInterfaces();

    default List<? extends TypeMirror> getPermittedSubclasses() {
        return List.of();
    }

    TypeMirror getErasureField();

    String getFlatName();

    ModuleElement getModuleElement();

    PackageElement getPackageElement();
}

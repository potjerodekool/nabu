package io.github.potjerodekool.nabu.compiler.lang.model.element;

import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;


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

    void addEnclosedElement(Element element);

    void setSuperClass(TypeMirror type);

    void complete();
}
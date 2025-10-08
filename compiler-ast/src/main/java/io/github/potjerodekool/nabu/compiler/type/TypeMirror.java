package io.github.potjerodekool.nabu.compiler.type;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;

import java.util.List;

public interface TypeMirror {

    TypeKind getKind();

    <R, P> R accept(TypeVisitor<R, P> visitor, P param);

    boolean equals(Object obj);

    default boolean isPrimitiveType() {
        return false;
    }

    default boolean isReferenceType() {
        return false;
    }

    default boolean isDeclaredType() {
        return false;
    }

    default List<? extends TypeMirror> getTypeArguments() {
        return List.of();
    }

    default TypeMirror getEnclosingType() {
        return null;
    }

    default List<? extends TypeMirror> getParameterTypes() {
        return List.of();
    }

    default TypeMirror getReturnType() {
        return null;
    }

    default TypeMirror getReceiverType() {
        return null;
    }

    default List<? extends TypeMirror> getThrownTypes() {
        return List.of();
    }

    default TypeMirror getUpperBound() {
        return null;
    }

    default TypeMirror getLowerBound() {
        return null;
    }

    default Element asElement() {
        return null;
    }

    default TypeElement asTypeElement() {
        return null;
    }

    default List<? extends TypeMirror> getAllParameters() {
        return List.of();
    }

    default boolean isRaw() {
        final var typeElement = asElement();
        return this != typeElement.asType()
                && !typeElement.asType().getAllParameters().isEmpty()
                && getAllParameters().isEmpty();
    }

    default boolean isParameterized() {
        return false;
    }

    default boolean isCompound() {
        return false;
    }

    default boolean isInterface() {
        return false;
    }

    String getClassName();

    default boolean isTypeVariable() {
        return false;
    }

    default boolean isArrayType() {
        return false;
    }
}

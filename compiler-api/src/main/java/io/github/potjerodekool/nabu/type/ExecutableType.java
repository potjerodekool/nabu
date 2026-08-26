package io.github.potjerodekool.nabu.type;

import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;

import java.util.List;

/**
 * An executable type. I.e. a method or constructor type.
 */
public interface ExecutableType extends TypeMirror {

    @Override
    List<? extends TypeMirror> getParameterTypes();

    List<? extends TypeVariable> getTypeVariables();

    void addTypeVariable(TypeVariable typeVariable);

    TypeElement getOwner();

    ExecutableElement getMethodSymbol();

    TypeMirror getReturnType();

    List<? extends TypeMirror> getThrownTypes();

    TypeMirror getReceiverType();
}

package io.github.potjerodekool.nabu.compiler.type;

import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;

import java.util.List;

public interface ExecutableType extends TypeMirror {

    @Override
    List<? extends TypeMirror> getParameterTypes();

    List<? extends TypeVariable> getTypeVariables();

    TypeElement getOwner();

    ExecutableElement getMethodSymbol();

    TypeMirror getReturnType();

    List<? extends TypeMirror> getThrownTypes();

    TypeMirror getReceiverType();
}

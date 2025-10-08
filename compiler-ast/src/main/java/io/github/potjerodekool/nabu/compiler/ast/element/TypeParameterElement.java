package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public interface TypeParameterElement extends Element {

    @Override
    TypeMirror asType();

    Element getGenericElement();

    List<? extends TypeMirror> getBounds();

    @Override
    Element getEnclosingElement();
}

package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public interface Element {

    String getSimpleName();

    ElementKind getKind();

    NestingKind getNestingKind();

    Element getEnclosingElement();

    List<Element> getEnclosedElements();

    void setEnclosingElement(Element abstractSymbol);

    void addEnclosedElement(Element enclosedElement);

    TypeMirror asType();
}

package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.Types;

import java.util.List;
import java.util.Set;

public interface Element {

    String getSimpleName();

    ElementKind getKind();

    Element getEnclosingElement();

    List<Element> getEnclosedElements();

    void setEnclosingElement(Element abstractSymbol);

    void addEnclosedElement(Element enclosedElement);

    TypeMirror asType();

    <R, P> R accept(ElementVisitor<R, P> v, P p);

    Set<Modifier> getModifiers();

    default boolean isPublic() {
        return getModifiers().contains(Modifier.PUBLIC);
    }

    default boolean isPrivate() {
        return getModifiers().contains(Modifier.PRIVATE);
    }

    default boolean isStatic() {
        return getModifiers().contains(Modifier.STATIC);
    }

    default boolean isSynthentic() {
        return getModifiers().contains(Modifier.SYNTHENTIC);
    }

    default boolean isAbstract() {
        return getModifiers().contains(Modifier.ABSTRACT);
    }

    <T> T getMetaData(ElementMetaData elementMetaData, Class<T> returnType);

    void setMetaData(ElementMetaData elementMetaData,
                     Object value);

    default TypeElement getClosestEnclosingClass() {
        Element element = this;

        while (element != null && element.getKind() != ElementKind.CLASS) {
            element = element.getEnclosingElement();
        }

        return (TypeElement) element;
    }

    TypeMirror erasure(final Types types);
}

package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;
import java.util.Set;

public interface Element extends AnnotatedConstruct {

    String getSimpleName();

    ElementKind getKind();

    Element getEnclosingElement();

    List<? extends Element> getEnclosedElements();

    TypeMirror asType();

    <R, P> R accept(ElementVisitor<R, P> v, P p);

    Set<Modifier> getModifiers();

    boolean hasFlag(long flag);

    boolean isPublic();

    boolean isPrivate();

    boolean isStatic();

    boolean isFinal();

    boolean isSynthetic();

    boolean isAbstract();

    boolean isNative();

    default boolean isType() {
        return false;
    }

    default TypeElement getClosestEnclosingClass() {
        Element element = this;

        while (element != null && element.getKind() != ElementKind.CLASS) {
            element = element.getEnclosingElement();
        }

        return (TypeElement) element;
    }

    default boolean exists() {
        return true;
    }

    CompoundAttribute attribute(TypeElement typeElement);

}

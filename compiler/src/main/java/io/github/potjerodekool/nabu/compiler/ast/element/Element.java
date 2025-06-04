package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.util.Types;

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

    TypeMirror erasure(final Types types);

    default boolean exists() {
        return true;
    }

    CompoundAttribute attribute(TypeElement typeElement);

}

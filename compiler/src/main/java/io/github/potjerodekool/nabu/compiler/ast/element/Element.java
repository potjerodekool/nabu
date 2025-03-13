package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.Types;

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

    default boolean hasFlag(final int flag) {
        return false;
    }

    default boolean isPublic() {
        return hasFlag(Flags.PUBLIC);
    }

    default boolean isPrivate() {
        return hasFlag(Flags.PRIVATE);
    }

    default boolean isStatic() {
        return hasFlag(Flags.STATIC);
    }

    default boolean isFinal() {
        return hasFlag(Flags.FINAL);
    }

    default boolean isSynthentic() {
        return hasFlag(Flags.SYNTHETIC);
    }

    default boolean isAbstract() {
        return getModifiers().contains(Modifier.ABSTRACT);
    }

    default boolean isNative() {
        return getModifiers().contains(Modifier.NATIVE);
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

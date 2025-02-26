package io.github.potjerodekool.nabu.compiler.type;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;

import java.util.List;

public interface DeclaredType extends ReferenceType {

    Element asElement();

    TypeMirror getEnclosingType();

    List<? extends TypeMirror> getTypeArguments();

    @Override
    default boolean isDeclaredType() {
        return true;
    }

    @Override
    default TypeElement getTypeElement() {
        return (TypeElement) asElement();
    }
}

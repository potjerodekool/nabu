package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.DeclaredType;

import java.util.Map;

public interface AnnotationMirror {

    DeclaredType getAnnotationType();

    Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues();
}

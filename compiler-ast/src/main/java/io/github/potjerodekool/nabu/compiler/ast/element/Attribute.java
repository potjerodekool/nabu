package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public interface Attribute extends AnnotationValue {

    boolean isSynthesized();

    TypeMirror getType();
}

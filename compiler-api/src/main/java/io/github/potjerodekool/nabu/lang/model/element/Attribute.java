package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.TypeMirror;

public interface Attribute extends AnnotationValue {

    boolean isSynthesized();

    TypeMirror getType();
}

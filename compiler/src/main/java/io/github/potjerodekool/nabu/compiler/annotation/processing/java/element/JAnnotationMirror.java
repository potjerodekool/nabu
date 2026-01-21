package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import java.util.Map;

public class JAnnotationMirror implements AnnotationMirror {

    public JAnnotationMirror(final io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror original) {
    }

    @Override
    public DeclaredType getAnnotationType() {
        return null;
    }

    @Override
    public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
        return Map.of();
    }
}

package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.ElementWrapperFactory;
import io.github.potjerodekool.nabu.tools.TodoException;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import java.util.Map;
import java.util.stream.Collectors;

public class JAnnotationMirror implements AnnotationMirror {

    private final io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror original;
    private final DeclaredType annotationType;
    private Map<ExecutableElement, AnnotationValue> elementValues;

    public JAnnotationMirror(final io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror annotationMirror) {
        this.original = annotationMirror;
        this.annotationType = (DeclaredType) TypeWrapperFactory.wrap(annotationMirror.getAnnotationType());
    }

    @Override
    public DeclaredType getAnnotationType() {
        return annotationType;
    }

    @Override
    public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
        if (elementValues == null) {
            this.elementValues = original.getElementValues().entrySet().stream()
                    .collect(Collectors.toMap(
                            it -> (ExecutableElement) ElementWrapperFactory.wrap(it.getKey()),
                            it -> ElementWrapperFactory.wrap(it.getValue())
                    ));
        }
        return elementValues;
    }
}

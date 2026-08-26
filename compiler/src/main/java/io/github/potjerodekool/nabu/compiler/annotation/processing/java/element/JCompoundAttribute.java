package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.lang.model.element.CompoundAttribute;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import java.util.AbstractMap;
import java.util.Map;
import java.util.stream.Collectors;

public class JCompoundAttribute extends JAttribute implements AnnotationMirror {

    private final CompoundAttribute original;
    private Map<ExecutableElement, AnnotationValue> values;
    private DeclaredType annotationType;

    public JCompoundAttribute(final CompoundAttribute original) {
        this.original = original;
    }

    @Override
    public Object getValue() {
        return null;
    }

    @Override
    public <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return v.visitAnnotation(this, p);
    }

    @Override
    public DeclaredType getAnnotationType() {
        if (annotationType == null) {
            annotationType = (DeclaredType) TypeWrapperFactory.wrap(original.getAnnotationType());
        }
        return annotationType;
    }

    @Override
    public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
        if (values == null) {
            final var map = (Map<io.github.potjerodekool.nabu.lang.model.element.ExecutableElement, io.github.potjerodekool.nabu.lang.model.element.AnnotationValue>) original.getElementValues();
            values = map.entrySet().stream()
                    .map(entry -> {
                        final var mappedKey = (ExecutableElement) ElementWrapperFactory.wrap(entry.getKey());
                        final var wrappedValue = ElementWrapperFactory.wrap(entry.getValue());

                        return new AbstractMap.SimpleEntry<>(mappedKey, wrappedValue);
                    })
                    .collect(Collectors.toMap(
                            AbstractMap.SimpleEntry::getKey,
                            AbstractMap.SimpleEntry::getValue
                    ));
        }
        return values;
    }
}

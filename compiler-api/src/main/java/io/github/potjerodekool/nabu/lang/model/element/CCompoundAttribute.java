package io.github.potjerodekool.nabu.lang.model.element;
import io.github.potjerodekool.nabu.type.DeclaredType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of CompoundAttribute.
 */
public final class CCompoundAttribute extends AbstractAttribute implements CompoundAttribute {

    private final DeclaredType annotationType;
    private final Map<ExecutableElement, AnnotationValue> values = new HashMap<>();

    public CCompoundAttribute(final DeclaredType annotationType,
                              final Map<ExecutableElement, AnnotationValue> values) {
        this.annotationType = annotationType;
        this.values.putAll(values);
        validateValues();
    }

    @Override
    public DeclaredType getAnnotationType() {
        return annotationType;
    }

    @Override
    public DeclaredType getType() {
        return getAnnotationType();
    }

    public void addValue(final ExecutableElement name,
                         final AnnotationValue value) {
        this.values.put(name, value);
        validateValues();
    }

    private void validateValues() {
        this.values.forEach((k, v) -> {
            Objects.requireNonNull(k);
            Objects.requireNonNull(v);
        });
    }

    @Override
    public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
        return values;
    }

}

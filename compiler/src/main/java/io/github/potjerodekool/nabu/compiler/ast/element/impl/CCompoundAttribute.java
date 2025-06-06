package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.AnnotationValue;
import io.github.potjerodekool.nabu.compiler.ast.element.CompoundAttribute;
import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;

import java.util.HashMap;
import java.util.Map;

public final class CCompoundAttribute extends AbstractAttribute implements CompoundAttribute {

    private final DeclaredType annotationType;
    private final Map<ExecutableElement, AnnotationValue> values = new HashMap<>();

    public CCompoundAttribute(final DeclaredType annotationType,
                              final Map<ExecutableElement, AnnotationValue> values) {
        this.annotationType = annotationType;
        this.values.putAll(values);
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
    }

    @Override
    public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
        return values;
    }

}

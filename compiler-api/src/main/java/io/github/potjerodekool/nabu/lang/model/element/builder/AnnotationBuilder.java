package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;
import java.util.Map;

public final class AnnotationBuilder {

    private AnnotationBuilder() {
    }

    public static CompoundAttribute createAnnotation(final DeclaredType annotationType,
                                                     final Map<ExecutableElement, AnnotationValue> values) {

        return new CCompoundAttribute(annotationType, values);
    }

    public static ArrayAttribute createArrayValue(final TypeMirror componentType,
                                                  final List<AnnotationValue> values) {
        return new CArrayAttribute(componentType, values);
    }

    public static ConstantAttribute createConstantValue(final Object value) {
        return new CConstantAttribute(value);
    }

    public static EnumAttribute createEnumValue(final DeclaredType enumType,
                                                final VariableElement enumValue) {
        return new CEnumAttribute(
                enumType,
                enumValue
        );
    }
}

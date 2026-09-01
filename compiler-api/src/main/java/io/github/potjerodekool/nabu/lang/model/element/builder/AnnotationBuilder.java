package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;
import java.util.Map;

/**
 * Builder for building annotations.
 */
public final class AnnotationBuilder {

    private AnnotationBuilder() {
    }

    /**
     * Create an annotation.
     * @param annotationType An annotation type
     * @param values Annotation values
     * @return Returns an annotation with the give values
     */
    public static CompoundAttribute createAnnotation(final DeclaredType annotationType,
                                                     final Map<ExecutableElement, AnnotationValue> values) {

        return new CCompoundAttribute(annotationType, values);
    }

    /**
     * Create an array value for an annotation.
     * @param componentType Component type of the array
     * @param values Values of the array
     * @return Returns an array with the given values
     */
    public static ArrayAttribute createArrayValue(final TypeMirror componentType,
                                                  final List<AnnotationValue> values) {
        return new CArrayAttribute(componentType, values);
    }

    /**
     * Create an constant value for an annotation.
     * @param value A constant value
     * @return Returns a constant value
     */
    public static ConstantAttribute createConstantValue(final Object value) {
        return new CConstantAttribute(value);
    }

    /**
     * Create an enum value for an annotation.
     * @param enumType Enumeration type
     * @param enumValue Enumvlaue
     * @return Return the enum value
     */
    public static EnumAttribute createEnumValue(final DeclaredType enumType,
                                                final VariableElement enumValue) {
        return new CEnumAttribute(
                enumType,
                enumValue
        );
    }

    /**
     * Create an class value for an annotation.
     * @param type A type
     * @return Returns a class type
     */
    public static ClassAttribute createClassAttribute(final TypeMirror type) {
        return new CClassAttribute(type);
    }
}

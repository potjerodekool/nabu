package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.TypeMirror;

/**
 * An attribute
 */
public sealed interface Attribute extends AnnotationValue
        permits AbstractAttribute, ArrayAttribute, ClassAttribute, CompoundAttribute, ConstantAttribute, EnumAttribute {

    /**
     * If the attribute is synthesized or not.
     * @return Returns true if this attribute is synthesized.
     */
    boolean isSynthesized();

    /**
     * The type of the attribute.
     * @return Returns the attribute type.
     */
    TypeMirror getType();
}

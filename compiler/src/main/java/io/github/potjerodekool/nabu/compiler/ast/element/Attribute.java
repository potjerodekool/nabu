package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.ast.element.impl.AbstractAttribute;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public sealed interface Attribute extends AnnotationValue
        permits AbstractAttribute, ArrayAttribute, CompoundAttribute, ConstantAttribute, EnumAttribute {

    boolean isSynthesized();

    TypeMirror getType();
}

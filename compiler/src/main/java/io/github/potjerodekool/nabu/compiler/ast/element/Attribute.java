package io.github.potjerodekool.nabu.compiler.ast.element;

public sealed interface Attribute extends AnnotationValue
        permits ArrayAttribute, CompoundAttribute, ConstantAttribute, EnumAttribute {

}

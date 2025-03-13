package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.ArrayAttribute;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.CompoundAttribute;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.ConstantAttribute;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.EnumAttribute;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;
import java.util.Map;

public final class AnnotationBuilder {

    private AnnotationBuilder() {
    }

    public static io.github.potjerodekool.nabu.compiler.ast.element.CompoundAttribute createAnnotation(final DeclaredType annotationType,
                                                                                                       final Map<ExecutableElement, AnnotationValue> values) {

        return new CompoundAttribute(annotationType, values);
    }

    public static io.github.potjerodekool.nabu.compiler.ast.element.ArrayAttribute createArrayValue(final TypeMirror componentType,
                                                                                                    final List<AnnotationValue> values) {
        return new ArrayAttribute(componentType, values);
    }

    public static io.github.potjerodekool.nabu.compiler.ast.element.ConstantAttribute createConstantValue(final Object value) {
        return new ConstantAttribute(value);
    }

    public static io.github.potjerodekool.nabu.compiler.ast.element.EnumAttribute createEnumValue(final DeclaredType enumType,
                                                                                                  final VariableElement enumValue) {
        return new EnumAttribute(
                enumType,
                enumValue
        );
    }
}

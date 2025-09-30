package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.CArrayAttribute;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.CCompoundAttribute;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.CConstantAttribute;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.CEnumAttribute;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

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

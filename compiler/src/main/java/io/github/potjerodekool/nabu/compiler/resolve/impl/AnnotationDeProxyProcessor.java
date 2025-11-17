package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.impl.AbstractAnnotationValueVisitor;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.type.DeclaredType;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Processor that deproxies an annotation because some things are not known
 * when reading an annotation from a .class file.
 */
public class AnnotationDeProxyProcessor extends AbstractAnnotationValueVisitor<Attribute, ExecutableElement> {

    public CompoundAttribute process(final AnnotationMirror annotationMirror) {
        final var annotationType = annotationMirror.getAnnotationType();
        final var methodMap = ElementFilter.methodsIn(annotationType.asTypeElement().getEnclosedElements()).stream()
                .collect(Collectors.toMap(
                        Element::getSimpleName,
                        Function.identity()
                ));

        final var newValues = annotationMirror.getElementValues().entrySet().stream()
                .map(entry -> {
                    final var method = methodMap.get(entry.getKey().getSimpleName());
                    final var newValue = deProxy(entry.getValue(), method);

                    final var annotationName = annotationType.asTypeElement().getQualifiedName();

                    if (method == null) {
                        throw new NullPointerException("Failed to resolve method " + entry.getKey().getSimpleName() + " for " + annotationName);
                    }

                    if (newValue == null) {
                        throw new NullPointerException(method.getSimpleName() + " for " + annotationName + " value is null");
                    }

                    return Map.entry(method, newValue);
                }).collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));

        return AnnotationBuilder.createAnnotation(
                annotationType,
                newValues
        );
    }

    private AnnotationValue deProxy(final AnnotationValue value,
                                    final ExecutableElement executableElement) {
        return switch (value) {
            case ArrayAttribute ignored -> value.accept(this, executableElement);
            case EnumAttribute ignored -> value.accept(this, executableElement);
            case CompoundAttribute ignored -> value.accept(this, executableElement);
            default -> value;
        };
    }

    @Override
    public Attribute visitAnnotation(final AnnotationMirror a,
                                     final ExecutableElement executableElement) {
        return this.process(a);
    }

    @Override
    public Attribute visitArray(final List<? extends AnnotationValue> values,
                                final ExecutableElement executableElement) {
        final var returnType = executableElement.getReturnType();
        final var newValues = values.stream()
                .map(value -> deProxy(value, executableElement))
                .toList();

        return AnnotationBuilder.createArrayValue(
                returnType,
                newValues
        );
    }

    @Override
    public Attribute visitEnumConstant(final VariableElement c,
                                       final ExecutableElement executableElement) {
        final var type = (DeclaredType) c.asType();
        final var variable = ElementFilter.enumConstantByName(type.asTypeElement(), c.getSimpleName())
                .orElse(c);

        return AnnotationBuilder.createEnumValue(
                type,
                variable
        );
    }

    @Override
    public Attribute visitUnknown(final AnnotationValue av,
                                  final ExecutableElement executableElement) {
        throw new IllegalArgumentException("Unsupported annotation value type: " + av.getClass().getName());
    }
}

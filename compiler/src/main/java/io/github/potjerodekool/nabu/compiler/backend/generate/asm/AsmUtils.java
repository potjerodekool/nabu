package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror;
import io.github.potjerodekool.nabu.lang.model.element.EnumAttribute;

public final class AsmUtils {

    private AsmUtils() {
    }

    public static boolean isVisible(final AnnotationMirror annotation) {
        final var annotationType = annotation.getAnnotationType();
        final var element = annotationType.asTypeElement();
        return element.getAnnotationMirrors().stream()
                .filter(AsmUtils::isRetentionAnnotation)
                .flatMap(it -> it.getElementValues().entrySet().stream())
                .filter(it ->
                        "value".equals(it.getKey().getSimpleName()))
                .map(it -> (EnumAttribute) it.getValue())
                .map(EnumAttribute::getValue)
                .anyMatch(it -> "RUNTIME".equals(it.getSimpleName()));
    }

    private static boolean isRetentionAnnotation(final AnnotationMirror annotation) {
        final var annotationType = annotation.getAnnotationType();
        return "java.lang.annotation.Retention".equals(
                annotationType.asTypeElement().getQualifiedName()
        );
    }
}

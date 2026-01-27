package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;

import java.util.Optional;

public abstract class AbstractAnnotationHandler implements AnnotationHandler {

    protected Optional<? extends AnnotationMirror> findAnnotation(final String className,
                                                                  final VariableElement field) {
        return field.getAnnotationMirrors().stream()
                .filter(annotation -> {
                    final var annotationClass = annotation.getAnnotationType().asTypeElement();
                    return className.equals(annotationClass.getQualifiedName());
                })
                .findFirst();
    }

    protected Optional<? extends AnnotationMirror> findAnnotation(final String className,
                                                                   final ClassSymbol classDeclaration) {
        return classDeclaration.getAnnotationMirrors().stream()
                .filter(annotation -> {
                    final var annotationClass = annotation.getAnnotationType().asTypeElement();
                    return className.equals(annotationClass.getQualifiedName());
                })
                .findFirst();
    }
}

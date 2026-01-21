package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok;

import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

import java.util.Optional;

public abstract class AbstractAnnotationHandler implements AnnotationHandler {

    protected Optional<? extends AnnotationTree> findAnnotation(final String className,
                                                                final VariableDeclaratorTree field) {
        return field.getAnnotations().stream()
                .filter(annotation -> {
                    final var annotationClass = annotation.getType().asTypeElement();
                    return className.equals(annotationClass.getQualifiedName());
                })
                .findFirst();
    }

    protected Optional<? extends AnnotationTree> findAnnotation(final String className,
                                                                final ClassDeclaration classDeclaration) {
        return classDeclaration.getModifiers().getAnnotations().stream()
                .filter(annotation -> {
                    final var annotationClass = annotation.getName().getType().asTypeElement();
                    return className.equals(annotationClass.getQualifiedName());
                })
                .findFirst();
    }
}

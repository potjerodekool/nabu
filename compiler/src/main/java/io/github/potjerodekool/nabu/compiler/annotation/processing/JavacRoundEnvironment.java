package io.github.potjerodekool.nabu.compiler.annotation.processing;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.ElementWrapperFactory;
import io.github.potjerodekool.nabu.tools.TodoException;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.stream.Collectors;

public class JavacRoundEnvironment implements RoundEnvironment {

    private final boolean processingOver;
    private final Set<? extends Element> rootElements;

    public JavacRoundEnvironment(final boolean processingOver,
                                 final Set<? extends Element> rootElements) {
        this.processingOver = processingOver;
        this.rootElements = rootElements;
    }

    @Override
    public boolean processingOver() {
        return processingOver;
    }

    @Override
    public boolean errorRaised() {
        throw new TodoException();
    }

    @Override
    public Set<? extends Element> getRootElements() {
        return rootElements;
    }

    @Override
    public Set<? extends Element> getElementsAnnotatedWith(final TypeElement annotationElement) {
        final var annotationClassName = annotationElement.getQualifiedName();
        return rootElements.stream()
                .filter(rootElement -> isAnnotatedWith(rootElement, annotationClassName))
                .collect(Collectors.toSet());
    }

    private boolean isAnnotatedWith(final Element rootElement,
                                    final Name annotationClassName) {
        return rootElement.getAnnotationMirrors().stream()
                .map(it -> (TypeElement) it.getAnnotationType().asElement())
                .map(TypeElement::getQualifiedName)
                .anyMatch(annotationType -> annotationType.contentEquals(annotationClassName));
    }

    @Override
    public Set<? extends Element> getElementsAnnotatedWith(final Class<? extends Annotation> a) {
        throw new TodoException();
    }
}

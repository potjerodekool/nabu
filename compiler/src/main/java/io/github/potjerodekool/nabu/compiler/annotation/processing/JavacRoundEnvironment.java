package io.github.potjerodekool.nabu.compiler.annotation.processing;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class JavacRoundEnvironment implements RoundEnvironment {

    private final boolean processingOver;
    private final Set<? extends Element> rootElements;
    private final Set<Element> allElements;

    public JavacRoundEnvironment(final boolean processingOver,
                                 final Set<? extends Element> rootElements) {
        this.processingOver = processingOver;
        this.rootElements = rootElements;
        this.allElements = collectAllElements(rootElements);
    }

    private Set<Element> collectAllElements(final Set<? extends Element> rootElements) {
        final var all = new HashSet<Element>();
        collectAllElements(rootElements, all);
        return all;
    }

    private void collectAllElements(final Iterable<? extends Element> elements,
                                    final Set<Element> result) {
        for (final var element : elements) {
            if (result.add(element)) {
                collectAllElements(element.getEnclosedElements(), result);
            }
        }
    }

    @Override
    public boolean processingOver() {
        return processingOver;
    }

    @Override
    public boolean errorRaised() {
        return false;
    }

    @Override
    public Set<? extends Element> getRootElements() {
        return rootElements;
    }

    @Override
    public Set<? extends Element> getElementsAnnotatedWith(final TypeElement annotationElement) {
        final var annotationClassName = annotationElement.getQualifiedName();
        return allElements.stream()
                .filter(rootElement -> isAnnotatedWith(rootElement, annotationClassName))
                .collect(Collectors.toSet());
    }

    private boolean isAnnotatedWith(final Element element,
                                    final Name annotationClassName) {
        return element.getAnnotationMirrors().stream()
                .map(it -> (TypeElement) it.getAnnotationType().asElement())
                .map(TypeElement::getQualifiedName)
                .anyMatch(annotationType -> annotationType.contentEquals(annotationClassName));
    }

    @Override
    public Set<? extends Element> getElementsAnnotatedWith(final Class<? extends Annotation> a) {
        final var annotationName = a.getName();
        return allElements.stream()
                .filter(element -> element.getAnnotationMirrors().stream()
                        .anyMatch(mirror -> mirror.getAnnotationType().toString().equals(annotationName)))
                .collect(Collectors.toSet());
    }
}
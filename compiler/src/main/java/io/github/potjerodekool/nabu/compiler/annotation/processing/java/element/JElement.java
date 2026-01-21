package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.lang.model.element.QualifiedNameable;
import io.github.potjerodekool.nabu.tools.TodoException;

import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class JElement<S extends io.github.potjerodekool.nabu.lang.model.element.Element> implements Element {

    private final S original;

    private Name simpleName;
    private Name qualifiedName;
    private Set<Modifier> modifiers;
    private ElementKind kind;
    private TypeMirror type;
    private Element enclosingElement;
    private List<? extends Element> enclosedElements;
    private List<? extends AnnotationMirror> annotationMirrors;

    protected JElement(final S original) {
        this.original = original;
    }

    public S getOriginal() {
        return original;
    }

    @Override
    public Name getSimpleName() {
        if (simpleName == null) {
            simpleName = new JName(original.getSimpleName());
        }
        return simpleName;
    }

    public Name getQualifiedName() {
        if (qualifiedName == null) {
            if (original instanceof QualifiedNameable qualifiedNameable) {
                qualifiedName = new JName(qualifiedNameable.getQualifiedName());
            } else {
                qualifiedName = getSimpleName();
            }
        }

        return qualifiedName;
    }


    @Override
    public Set<Modifier> getModifiers() {
        if (modifiers == null) {
            modifiers = original.getModifiers().stream()
                    .map(modifier -> Modifier.valueOf(modifier.name()))
                    .collect(Collectors.toSet());
        }
        return modifiers;
    }

    public ElementKind getKind() {
        if (kind == null) {
            kind = ElementKind.valueOf(original.getKind().name());
        }

        return kind;
    }

    @Override
    public TypeMirror asType() {
        if (type == null) {
            type = TypeWrapperFactory.wrap(original.asType());
        }
        return type;
    }

    @Override
    public Element getEnclosingElement() {
        if (enclosingElement == null) {
            enclosingElement = ElementWrapperFactory.wrap(original.getEnclosingElement());
        }
        return enclosingElement;
    }

    public List<? extends Element> getEnclosedElements() {
        if (enclosedElements == null) {
            enclosedElements = original.getEnclosedElements().stream()
                    .map(ElementWrapperFactory::wrap)
                    .toList();
        }
        return enclosedElements;
    }

    public List<? extends AnnotationMirror> getAnnotationMirrors() {
        if (annotationMirrors == null) {
            annotationMirrors = original.getAnnotationMirrors().stream()
                    .map(TypeWrapperFactory::wrap)
                    .toList();
        }
        return annotationMirrors;
    }

    @Override
    public <A extends Annotation> A getAnnotation(final Class<A> annotationType) {
        throw new TodoException();
    }

    @Override
    public <A extends Annotation> A[] getAnnotationsByType(final Class<A> annotationType) {
        throw new TodoException();
    }
}

package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.ElementWrapperFactory;
import io.github.potjerodekool.nabu.tools.TodoException;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.List;

public abstract class JAbstractType<T extends io.github.potjerodekool.nabu.type.TypeMirror> implements TypeMirror {

    private final TypeKind kind;
    private final T original;
    protected final Element element;

    public JAbstractType(final TypeKind kind,
                         final T original) {
        this(kind, original, original != null ? ElementWrapperFactory.wrap(original.asElement()) : null);
    }

    public JAbstractType(final TypeKind kind,
                         final T original,
                         final Element element) {
        this.kind = kind;
        this.original = original;
        this.element = element;
    }

    public T getOriginal() {
        return original;
    }

    @Override
    public TypeKind getKind() {
        return kind;
    }

    public Element asElement() {
        return element;
    }

    @Override
    public List<? extends AnnotationMirror> getAnnotationMirrors() {
        throw new TodoException();
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

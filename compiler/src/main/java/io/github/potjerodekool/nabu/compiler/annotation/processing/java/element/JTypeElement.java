package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;

import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.List;

public class JTypeElement extends JElement<io.github.potjerodekool.nabu.compiler.lang.model.element.TypeElement> implements javax.lang.model.element.TypeElement {

    private List<? extends TypeMirror> interfaces;
    private final NestingKind nestingKind;
    private List<TypeParameterElement> typeParameterElements;

    protected JTypeElement(final io.github.potjerodekool.nabu.compiler.lang.model.element.TypeElement original) {
        super(original);
        this.nestingKind = NestingKind.valueOf(original.getNestingKind().name());
    }

    @Override
    public <A extends Annotation> A getAnnotation(final Class<A> annotationType) {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends Annotation> A[] getAnnotationsByType(final Class<A> annotationType) {
        return (A[]) new Annotation[0];
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitType(this, p);
    }

    @Override
    public NestingKind getNestingKind() {
        return nestingKind;
    }

    @Override
    public TypeMirror getSuperclass() {
        return TypeWrapperFactory.wrap(getOriginal().getSuperclass());
    }

    @Override
    public List<? extends TypeMirror> getInterfaces() {
        if (interfaces == null) {
            interfaces = getOriginal().getInterfaces().stream()
                    .map(TypeWrapperFactory::wrap)
                    .toList();
        }
        return interfaces;
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
        if (typeParameterElements == null) {
            typeParameterElements = getOriginal().getTypeParameters().stream()
                    .map(ElementWrapperFactory::wrap)
                    .map(it -> (TypeParameterElement) it)
                    .toList();
        }
        return typeParameterElements;
    }

    @Override
    public String toString() {
        return getOriginal().getQualifiedName();
    }
}

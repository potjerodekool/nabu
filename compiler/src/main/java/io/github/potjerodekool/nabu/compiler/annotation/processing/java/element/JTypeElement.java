package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.tools.TodoException;

import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class JTypeElement extends JElement<TypeElement> implements javax.lang.model.element.TypeElement {

    private final Logger logger = Logger.getLogger(getClass().getName());
    private List<? extends TypeMirror> interfaces;
    private final NestingKind nestingKind;
    private List<TypeParameterElement> typeParameterElements;

    protected JTypeElement(final TypeElement original) {
        super(original);
        this.nestingKind = NestingKind.valueOf(original.getNestingKind().name());
    }

    @Override
    public <A extends Annotation> A getAnnotation(final Class<A> annotationType) {
        logger.log(LogLevel.ERROR, "getAnnotation");
        throw new TodoException();
    }

    @Override
    public <A extends Annotation> A[] getAnnotationsByType(final Class<A> annotationType) {
        logger.log(LogLevel.ERROR, "getAnnotationsByType");
        throw new TodoException();
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

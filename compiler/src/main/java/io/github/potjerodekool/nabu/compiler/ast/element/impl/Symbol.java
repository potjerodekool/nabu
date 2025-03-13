package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AnnotationDeProxyProcessor;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.Types;

import java.lang.annotation.Annotation;
import java.util.*;

public abstract class Symbol implements Element {

    private final String simpleName;

    private final ElementKind kind;

    private Element enclosingElement;

    private final List<Symbol> enclosedElements = new ArrayList<>();

    private TypeMirror type;

    private final long flags;

    private final Map<String, Object> metaData = new HashMap<>();

    protected TypeMirror erasureType;

    private final List<AnnotationMirror> annotationMirrors = new ArrayList<>();

    private boolean deProxyAnnotations = true;

    public Symbol(final ElementKind kind,
                  final long flags,
                  final String name,
                  final Element owner) {
        this.kind = kind;
        this.flags = flags;
        this.simpleName = name;
        this.enclosingElement = owner;
    }

    @Override
    public String getSimpleName() {
        return simpleName;
    }

    @Override
    public ElementKind getKind() {
        return kind;
    }

    @Override
    public Element getEnclosingElement() {
        return enclosingElement;
    }

    public void setEnclosingElement(final Element enclosingElement) {
        if (enclosingElement == null && this.enclosingElement != null) {
            throw new IllegalStateException();
        }

        this.enclosingElement = enclosingElement;
    }

    @Override
    public List<Symbol> getEnclosedElements() {
        return enclosedElements;
    }

    public void addEnclosedElement(final Symbol enclosedElement) {
        this.enclosedElements.add(enclosedElement);
        enclosedElement.setEnclosingElement(this);
    }

    @Override
    public TypeMirror asType() {
        return type;
    }

    public void setType(final TypeMirror type) {
        this.type = type;
    }

    @Override
    public Set<Modifier> getModifiers() {
        return Flags.createModifiers(this.flags);
    }

    @Override
    public boolean hasFlag(final int flag) {
        return Flags.hasFlag(flags, flag);
    }

    public long getFlags(){
        return flags;
    }

    private String resolveKey(final ElementMetaData elementMetaData) {
        if (elementMetaData instanceof Enum<?> enumValue) {
            final var className = elementMetaData.getClass().getName();
            final var name = enumValue.name();
            return className + "." + name;
        } else {
            throw new IllegalArgumentException("Expected an enum value");
        }
    }

    @Override
    public <T> T getMetaData(final ElementMetaData elementMetaData, final Class<T> returnType) {
        final var value = this.metaData.get(resolveKey(elementMetaData));
        return value != null
                ? returnType.cast(value)
                : null;
    }

    @Override
    public void setMetaData(final ElementMetaData elementMetaData,
                            final Object value) {
        this.metaData.put(resolveKey(elementMetaData), value);
    }

    public TypeMirror erasure(final Types types) {
        if (erasureType == null)
            erasureType = types.erasure(type);
        return erasureType;
    }

    @Override
    public List<? extends AnnotationMirror> getAnnotationMirrors() {
        deProxyAnnotations();
        return annotationMirrors;
    }

    private void deProxyAnnotations() {
        if (deProxyAnnotations) {
            final var processor = new AnnotationDeProxyProcessor();
            final var newAnnotations = this.annotationMirrors.stream()
                    .map(processor::process)
                    .toList();
            this.annotationMirrors.clear();
            this.annotationMirrors.addAll(newAnnotations);
            deProxyAnnotations = false;
        }
    }

    public void setAnnotations(final List<AnnotationMirror> annotations) {
        this.deProxyAnnotations = true;
        this.annotationMirrors.addAll(annotations);
    }

    public void addAnnotationMirror(final AnnotationMirror annotationMirror) {
        this.deProxyAnnotations = true;
        this.annotationMirrors.add(annotationMirror);
    }

    @Override
    public <A extends Annotation> A getAnnotation(final Class<A> annotationType) {
        return null;
    }

    @Override
    public <A extends Annotation> A[] getAnnotationsByType(final Class<A> annotationType) {
        return null;
    }

    public abstract <R, P> R accept(SymbolVisitor<R, P> v, P p);

}

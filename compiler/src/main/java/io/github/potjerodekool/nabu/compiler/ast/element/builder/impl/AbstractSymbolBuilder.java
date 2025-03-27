package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.AnnotationMirror;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractSymbolBuilder<E, EB extends AbstractSymbolBuilder<E, EB>> {

    protected String name;
    protected ElementKind kind;
    protected Element enclosingElement;
    private final List<E> enclosedElements = new ArrayList<>();
    protected long flags = 0;
    protected final List<AnnotationMirror> annotations = new ArrayList<>();

    public AbstractSymbolBuilder() {
    }

    protected AbstractSymbolBuilder(final Symbol original) {
        this.name = original.getSimpleName();
        this.kind = original.getKind();
        this.enclosingElement = original.getEnclosingElement();
        this.flags = original.getFlags();
        this.annotations.addAll(original.getAnnotationMirrors());
    }

    public long getFlags() {
        return flags;
    }

    public EB flags(final long flags) {
        this.flags = flags;
        return self();
    }

    public ElementKind getKind() {
        return kind;
    }

    public EB kind(final ElementKind kind) {
        this.kind = kind;
        return self();
    }

    public String getName() {
        return name;
    }

    public EB name(final String name) {
        this.name = name;
        return self();
    }

    public List<? extends AnnotationMirror> getAnnotations() {
        return annotations;
    }

    public EB annotations(final List<AnnotationMirror> annotations) {
        this.annotations.clear();
        this.annotations.addAll(annotations);
        return self();
    }

    public Element getEnclosingElement() {
        return enclosingElement;
    }

    public EB enclosingElement(final Element enclosingElement) {
        this.enclosingElement = enclosingElement;
        return self();
    }

    public List<E> getEnclosedElements() {
        return enclosedElements;
    }

    public EB enclosedElement(final E enclosedElement) {
        this.enclosedElements.add(enclosedElement);
        return self();
    }

    public EB enclosedElements(final List<E> enclosedElements) {
        this.enclosedElements.clear();
        this.enclosedElements.addAll(enclosedElements);
        return self();
    }

    protected abstract EB self();

}

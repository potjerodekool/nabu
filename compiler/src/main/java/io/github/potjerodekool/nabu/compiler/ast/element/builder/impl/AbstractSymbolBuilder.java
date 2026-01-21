package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.builder.ElementBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public abstract class AbstractSymbolBuilder<EB extends ElementBuilder<EB>> implements ElementBuilder<EB> {

    protected String simpleName;
    protected ElementKind kind;
    protected Element enclosingElement;
    private final List<Symbol> enclosedElements = new ArrayList<>();
    protected long flags = 0;
    protected final List<AnnotationMirror> annotations = new ArrayList<>();

    public AbstractSymbolBuilder() {
    }

    protected AbstractSymbolBuilder(final Symbol original) {
        this.simpleName = original.getSimpleName();
        this.kind = original.getKind();
        this.enclosingElement = original.getEnclosingElement();
        this.flags = original.getFlags();
        this.annotations.addAll(original.getAnnotationMirrors());
    }

    @Override
    public long getFlags() {
        return flags;
    }

    public EB flags(final long flags) {
        this.flags = flags;
        return self();
    }

    @Override
    public ElementKind getKind() {
        return kind;
    }

    public EB kind(final ElementKind kind) {
        this.kind = kind;
        return self();
    }

    public String getSimpleName() {
        return simpleName;
    }

    public EB simpleName(final String name) {
        this.simpleName = name;
        return self();
    }

    public List<? extends AnnotationMirror> getAnnotations() {
        return annotations;
    }

    @Override
    public EB annotations(final AnnotationMirror... annotations) {
        return this.annotations(Arrays.asList(annotations));
    }

    @Override
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

    public List<? extends Symbol> getEnclosedElements() {
        return enclosedElements;
    }

    @Override
    public EB enclosedElement(final Element enclosedElement) {
        this.enclosedElements.add((Symbol)enclosedElement);
        return self();
    }

    public EB enclosedElements(final List<? extends Element> enclosedElements) {
        this.enclosedElements.clear();
        this.enclosedElements.addAll((Collection<? extends Symbol>) enclosedElements);
        return self();
    }

    protected abstract EB self();

}

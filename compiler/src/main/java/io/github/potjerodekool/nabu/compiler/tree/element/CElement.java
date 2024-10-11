package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class CElement<E extends CElement<E>> extends Tree {

    private Kind kind;

    private String simpleName;

    private CElement<?> enclosingElement;

    private final List<CElement<?>> enclosedElements = new ArrayList<>();

    private final Set<CModifier> modifiers = new HashSet<>();

    public String getSimpleName() {
        return simpleName;
    }

    public E simpleName(final String simpleName) {
        this.simpleName = simpleName;
        return self();
    }

    public CElement<?> getEnclosingElement() {
        return enclosingElement;
    }

    public E enclosingElement(final CElement<?> enclosingElement) {
        this.enclosingElement = enclosingElement;
        return self();
    }

    public List<CElement<?>> getEnclosedElements() {
        return enclosedElements;
    }

    public E enclosedElement(final CElement<?> element) {
        this.enclosedElements.add(element);
        element.enclosingElement(this);
        return self();
    }

    public E enclosedElement(final CElement<?> element,
                             final int index) {
        this.enclosedElements.add(index, element);
        element.enclosingElement(this);
        return self();
    }

    public Kind getKind() {
        return kind;
    }

    public E kind(final Kind kind) {
        this.kind = kind;
        return self();
    }

    protected E self() {
        return (E)this;
    }

    public Set<CModifier> getModifiers() {
        return modifiers;
    }

    public boolean hasModifier(final CModifier modifier) {
        return this.modifiers.contains(modifier);
    }

    public enum Kind {
        PARAMETER,
        LOCAL_VARIABLE,
        METHOD,
        CONSTRUCTOR
    }
}

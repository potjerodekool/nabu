package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class Element<E extends Element<E>> extends Tree {

    private Kind kind;

    private String simpleName;

    private Element<?> enclosingElement;

    private final List<Element<?>> enclosedElements = new ArrayList<>();

    private final Set<CModifier> modifiers = new HashSet<>();

    public Element(final int lineNumber,
                   final int columnNumber) {
        super(lineNumber, columnNumber);
    }

    public Element(final CElementBuilder<?, ?> builder) {
        super(builder);
        this.simpleName = builder.simpleName;
        this.kind = builder.kind;
        this.modifiers.addAll(builder.modifiers);
        builder.enclosedElements.forEach(this::enclosedElement);
    }

    public String getSimpleName() {
        return simpleName;
    }

    public E simpleName(final String simpleName) {
        this.simpleName = simpleName;
        return self();
    }

    public Element<?> getEnclosingElement() {
        return enclosingElement;
    }

    public E enclosingElement(final Element<?> enclosingElement) {
        this.enclosingElement = enclosingElement;
        return self();
    }

    public List<Element<?>> getEnclosedElements() {
        return enclosedElements;
    }

    public E enclosedElement(final Element<?> element) {
        this.enclosedElements.add(element);
        element.enclosingElement(this);
        return self();
    }

    public E enclosedElement(final Element<?> element,
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

    protected abstract E self();

    public Set<CModifier> getModifiers() {
        return modifiers;
    }

    public boolean hasModifier(final CModifier modifier) {
        return this.modifiers.contains(modifier);
    }

    public E modifier(final CModifier modifier) {
        this.modifiers.add(modifier);
        return self();
    }

    public E modifiers(final List<CModifier> modifiers) {
        this.modifiers.addAll(modifiers);
        return self();
    }

    public enum Kind {
        PARAMETER,
        LOCAL_VARIABLE,
        METHOD,
        CONSTRUCTOR
    }

    public static abstract class CElementBuilder<E extends Element<E>, CB extends CElementBuilder<E, CB>> extends TreeBuilder<E, CB> {
        private String simpleName;
        private Kind kind;
        private final List<CModifier> modifiers = new ArrayList<>();
        private final List<Element<?>> enclosedElements = new ArrayList<>();

        public CElementBuilder() {
        }

        public CElementBuilder(final Element<E> element) {
            this.simpleName = element.getSimpleName();
            this.kind = element.getKind();
            this.modifiers.addAll(element.getModifiers());
            this.enclosedElements.addAll(element.getEnclosedElements());
        }

        public CB simpleName(final String simpleName) {
            this.simpleName = simpleName;
            return self();
        }

        public CB kind(final Kind kind) {
            this.kind = kind;
            return self();
        }

        public CB modifier(final CModifier modifier) {
            this.modifiers.add(modifier);
            return self();
        }

        public CB modifiers(final List<CModifier> modifiers) {
            this.modifiers.addAll(modifiers);
            return self();
        }


        public CB enclosedElement(final Element<?> element) {
            this.enclosedElements.add(element);
            return self();
        }

        public CB enclosedElements(final List<Element<?>> enclosedElements) {
            this.enclosedElements.addAll(enclosedElements);
            return self();
        }

        public String getSimpleName() {
            return simpleName;
        }

    }
}

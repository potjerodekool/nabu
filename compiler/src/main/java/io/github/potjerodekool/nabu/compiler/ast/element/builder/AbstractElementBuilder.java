package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.Modifier;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public abstract class AbstractElementBuilder<B extends AbstractElementBuilder<B>> {

    protected String name;
    protected ElementKind kind;
    protected Element enclosing;
    protected final Set<Modifier> modifiers = new HashSet<>();

    public B name(final String name) {
        this.name = name;
        return self();
    }

    public B kind(final ElementKind kind) {
        this.kind = kind;
        return self();
    }

    public B enclosingElement(final Element enclosedElement) {
        this.enclosing = enclosedElement;
        return self();
    }

    public B modifiers(final Collection<Modifier> modifiers) {
        this.modifiers.addAll(modifiers);
        return self();
    }

    public B modifiers(final Modifier... modifiers) {
        return modifiers(Set.of(modifiers));
    }

    protected abstract B self();

}

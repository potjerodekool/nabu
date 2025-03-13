package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.AnnotationMirror;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.Modifier;

import java.util.*;

public abstract class AbstractElementBuilder<B extends AbstractElementBuilder<B>> {

    protected String name;
    protected ElementKind kind;
    protected Element enclosing;
    private long flags = 0;
    protected final List<AnnotationMirror> annotations = new ArrayList<>();

    protected long getFlags() {
        return flags;
    }

    protected Set<Modifier> getModifiers() {
        return Flags.createModifiers(flags);
    }

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
        this.flags = Flags.parse(modifiers);
        return self();
    }

    public B modifiers(final Modifier... modifiers) {
        return modifiers(Set.of(modifiers));
    }

    protected abstract B self();

    public B annotations(final List<AnnotationMirror> annotations) {
        this.annotations.addAll(annotations);
        return self();
    }
}

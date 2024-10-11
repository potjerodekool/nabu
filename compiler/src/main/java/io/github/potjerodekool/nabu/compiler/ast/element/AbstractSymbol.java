package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class AbstractSymbol implements Element {

    private final String simpleName;

    private final ElementKind kind;

    private final NestingKind nestingKind;

    private Element enclosingElement;

    private final List<Element> enclosedElements = new ArrayList<>();

    private TypeMirror type;

    private final Set<Modifier> modifiers = new HashSet<>();

    public AbstractSymbol(final ElementKind kind,
                          final String name,
                          final AbstractSymbol owner) {
        this(kind, null, name, owner);
    }

    public AbstractSymbol(final ElementKind kind,
                          final NestingKind nestingKind,
                          final String name,
                          final AbstractSymbol owner) {
        this.kind = kind;
        this.nestingKind = nestingKind;
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
    public NestingKind getNestingKind() {
        return nestingKind;
    }

    @Override
    public Element getEnclosingElement() {
        return enclosingElement;
    }

    @Override
    public void setEnclosingElement(final Element enclosingElement) {
        if (nestingKind == NestingKind.MEMBER && enclosingElement instanceof PackageElement) {
            throw new IllegalArgumentException();
        }

        this.enclosingElement = enclosingElement;
    }

    @Override
    public List<Element> getEnclosedElements() {
        return enclosedElements;
    }

    @Override
    public void addEnclosedElement(final Element enclosedElement) {
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

    public boolean isPublic() {
        return modifiers.contains(Modifier.PUBLIC);
    }

    public boolean isPrivate() {
        return modifiers.contains(Modifier.PRIVATE);
    }

    public boolean isStatic() {
        return modifiers.contains(Modifier.STATIC);
    }

    public boolean isSynthentic() {
        return modifiers.contains(Modifier.SYNTHENTIC);
    }

    public Set<Modifier> getModifiers() {
        return modifiers;
    }

    public void addModifier(final Modifier modifier) {
        this.modifiers.add(modifier);
    }

    public void addModifiers(final Modifier... modifiers) {
        addModifiers(Set.of(modifiers));
    }

    public void addModifiers(final Set<Modifier> modifiers) {
        this.modifiers.addAll(modifiers);
    }
}

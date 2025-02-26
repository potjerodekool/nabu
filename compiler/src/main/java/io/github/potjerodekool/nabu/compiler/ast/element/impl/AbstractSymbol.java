package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementMetaData;
import io.github.potjerodekool.nabu.compiler.ast.element.Modifier;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.Types;

import java.util.*;

public abstract class AbstractSymbol implements Element {

    private final String simpleName;

    private final ElementKind kind;

    private Element enclosingElement;

    private final List<Element> enclosedElements = new ArrayList<>();

    private TypeMirror type;

    private final Set<Modifier> modifiers;

    private final Map<String, Object> metaData = new HashMap<>();

    protected TypeMirror erasureType;

    public AbstractSymbol(final ElementKind kind,
                          final Set<Modifier> modifiers,
                          final String name,
                          final Element owner) {
        this.kind = kind;
        this.modifiers = Collections.unmodifiableSet(modifiers);
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

    @Override
    public void setEnclosingElement(final Element enclosingElement) {
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

    @Override
    public Set<Modifier> getModifiers() {
        return modifiers;
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
}

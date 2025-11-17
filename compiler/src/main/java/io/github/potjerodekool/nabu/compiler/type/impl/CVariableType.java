package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVisitor;
import io.github.potjerodekool.nabu.type.VariableType;

import java.util.Objects;

public class CVariableType extends AbstractType implements VariableType {

    private final TypeMirror interferedType;

    public CVariableType(final TypeMirror interferedType) {
        super(null);
        this.interferedType = interferedType;
    }

    @Override
    public TypeKind getKind() {
        return null;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitVariableType(this, param);
    }

    @Override
    public String getClassName() {
        return interferedType != null
                ? interferedType.getClassName()
                : "var";
    }

    @Override
    public TypeMirror getInterferedType() {
        return interferedType;
    }

    @Override
    public String toString() {
        return getClassName();
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof CVariableType other
                && Objects.equals(interferedType, other.interferedType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(interferedType);
    }

    @Override
    public TypeElement asTypeElement() {
        if (interferedType == null) {
            return null;
        } else {
            return interferedType.asTypeElement();
        }
    }

    @Override
    public Element asElement() {
        if (interferedType == null) {
            return null;
        } else {
            return interferedType.asElement();
        }
    }
}

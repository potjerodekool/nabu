package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVisitor;

import java.util.List;

public class UndetVarType extends AbstractType {

    private final TypeMirror delegate;

    public UndetVarType(final TypeMirror delegate) {
        super(null);
        this.delegate = delegate;
    }

    @Override
    public boolean equals(final Object obj) {
        return false;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.NONE;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitUnknownType(this, param);
    }

    @Override
    public List<? extends TypeMirror> getParameterTypes() {
        return delegate.getParameterTypes();
    }

    @Override
    public String getClassName() {
        return "";
    }
}

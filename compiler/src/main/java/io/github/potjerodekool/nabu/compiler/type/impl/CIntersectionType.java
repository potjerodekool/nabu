package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;

public class CIntersectionType extends AbstractType implements IntersectionType {

    private final List<TypeMirror> bounds = new ArrayList<>();

    public CIntersectionType(final List<TypeMirror> bounds) {
        this.bounds.addAll(bounds);
    }

    @Override
    public List<? extends TypeMirror> getBounds() {
        return bounds;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.INTERSECTION;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitIntersectionType(this, param);
    }
}

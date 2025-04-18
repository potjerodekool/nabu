package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class CIntersectionType extends AbstractType implements IntersectionType {

    private final List<TypeMirror> bounds = new ArrayList<>();

    public CIntersectionType(final List<TypeMirror> bounds) {
        super(null);
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

    @Override
    public String getClassName() {
        return bounds.stream()
                .map(TypeMirror::getClassName)
                .collect(Collectors.joining(" | "));
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof final CIntersectionType that)) {
            return false;
        }
        return Objects.equals(bounds, that.bounds);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(bounds);
    }
}

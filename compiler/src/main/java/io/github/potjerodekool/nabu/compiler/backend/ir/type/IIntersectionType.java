package io.github.potjerodekool.nabu.compiler.backend.ir.type;

import java.util.ArrayList;
import java.util.List;

public final class IIntersectionType extends IType {

    private final List<IType> bounds = new ArrayList<>();

    public IIntersectionType(final List<IType> bounds) {
        super(ITypeKind.INTERSECTION);
        this.bounds.addAll(bounds);
    }

    public List<? extends IType> getBounds() {
        return bounds;
    }

    @Override
    public <R, P> R accept(final ITypeVisitor<R, P> visitor, final P param) {
        return visitor.visitIntersectionType(this, param);
    }
}

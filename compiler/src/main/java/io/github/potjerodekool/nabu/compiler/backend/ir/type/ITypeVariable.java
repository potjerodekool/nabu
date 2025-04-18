package io.github.potjerodekool.nabu.compiler.backend.ir.type;

import java.util.Objects;

public final class ITypeVariable extends IType {

    private final String name;
    private final IType upperBound;
    private final IType lowerBound;

    public ITypeVariable(final String name,
                         final IType upperBound,
                         final IType lowerBound) {
        super(ITypeKind.TYPEVAR);
        this.name = name;
        this.upperBound = upperBound;
        this.lowerBound = lowerBound;
    }

    public String getName() {
        return name;
    }

    public IType getUpperBound() {
        return upperBound;
    }

    public IType getLowerBound() {
        return lowerBound;
    }

    @Override
    public <R, P> R accept(final ITypeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeVariableType(this, param);
    }

    @Override
    public boolean equals(final Object o) {
        if (o instanceof ITypeVariable other) {
            return this.name.equals(other.name)
                    && Objects.equals(upperBound, other.upperBound)
                    && Objects.equals(lowerBound, other.lowerBound);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                name,
                upperBound,
                lowerBound
        );
    }
}

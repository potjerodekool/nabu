package io.github.potjerodekool.nabu.compiler.backend.ir.type;

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
}

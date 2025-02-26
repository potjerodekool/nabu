package io.github.potjerodekool.nabu.compiler.backend.ir.type;

public final class IPrimitiveType extends IType {

    public static final IType VOID = new IPrimitiveType(ITypeKind.VOID);
    public static final IType BOOLEAN = new IPrimitiveType(ITypeKind.BOOLEAN);
    public static final IType INT = new IPrimitiveType(ITypeKind.INT);
    public static final IType FLOAT = new IPrimitiveType(ITypeKind.FLOAT);
    public static final IType DOUBLE = new IPrimitiveType(ITypeKind.DOUBLE);
    public static final IType SHORT = new IPrimitiveType(ITypeKind.SHORT);
    public static final IType LONG = new IPrimitiveType(ITypeKind.LONG);
    public static final IType CHAR = new IPrimitiveType(ITypeKind.CHAR);
    public static final IType BYTE = new IPrimitiveType(ITypeKind.BYTE);

    private IPrimitiveType(final ITypeKind kind) {
        super(kind);
    }

    public static IType getType(final Object value) {
        return switch (value) {
            case Boolean ignored -> BOOLEAN;
            case Integer ignored -> INT;
            case Float ignored -> FLOAT;
            case Double ignored -> DOUBLE;
            case Short ignored -> SHORT;
            case Long ignored -> LONG;
            case Character ignored -> CHAR;
            case Byte ignored -> BYTE;
            case null, default -> null;
        };
    }

    @Override
    public <R, P> R accept(final ITypeVisitor<R, P> visitor, final P param) {
        return visitor.visitPrimitiveType(this, param);
    }
}

package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.TypeSymbol;
import io.github.potjerodekool.nabu.compiler.type.*;

public class CWildcardType extends AbstractType implements WildcardType {

    private final TypeMirror type;
    private final BoundKind kind;
    private final TypeVariable bound;

    public CWildcardType(final TypeMirror type,
                         final BoundKind kind,
                         final TypeSymbol typeSymbol) {
        this(type, kind, typeSymbol,null);
    }

    public CWildcardType(final TypeMirror type,
                         final BoundKind kind,
                         final TypeSymbol typeSymbol,
                         final TypeVariable bound) {
        super(typeSymbol);
        this.type = type;
        this.kind = kind;
        this.bound = bound;
    }

    @Override
    public TypeMirror getExtendsBound() {
        return BoundKind.EXTENDS == kind
                ? type
                : null;
    }

    @Override
    public TypeMirror getSuperBound() {
        return BoundKind.SUPER == kind
                ? type
                : null;
    }

    @Override
    public TypeMirror getBound() {
        return type;
    }

    @Override
    public BoundKind getBoundKind() {
        return kind;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.WILDCARD;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitWildcardType(this, param);
    }

    @Override
    public String getClassName() {
        return switch (kind) {
            case UNBOUND -> "?";
            case EXTENDS -> "? extends " + type.getClassName();
            case SUPER -> "? super " + type.getClassName();
        };
    }
}

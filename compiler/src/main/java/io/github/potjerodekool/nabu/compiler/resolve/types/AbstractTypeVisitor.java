package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.type.*;

public abstract class AbstractTypeVisitor<R,P> implements TypeVisitor<R, P> {

    public final R visit(final TypeMirror typeMirror, P param) {
        return typeMirror.accept(this, param);
    }

    @Override
    public R visitDeclaredType(final DeclaredType declaredType, final P param) {
        return visitType(declaredType, param);
    }

    @Override
    public R visitArrayType(final ArrayType arrayType, final P param) {
        return visitType(arrayType, param);
    }

    @Override
    public R visitMethodType(final ExecutableType methodType, final P param) {
        return visitType(methodType, param);
    }

    @Override
    public R visitNoType(final NoType noType, final P param) {
        return visitType(noType, param);
    }

    @Override
    public R visitPrimitiveType(final PrimitiveType primitiveType, final P param) {
        return visitType(primitiveType, param);
    }

    @Override
    public R visitNullType(final NullType nullType, final P param) {
        return visitType(nullType, param);
    }

    @Override
    public R visitVariableType(final VariableType variableType, final P param) {
        return visitType(variableType, param);
    }

    @Override
    public R visitWildcardType(final WildcardType wildcardType, final P param) {
        return visitType(wildcardType, param);
    }

    @Override
    public R visitTypeVariable(final TypeVariable typeVariable, final P param) {
        return visitType(typeVariable, param);
    }

    @Override
    public R visitIntersectionType(final IntersectionType intersectionType, final P param) {
        return visitType(intersectionType, param);
    }

    @Override
    public R visitNoneType(final NoType noType, final P param) {
        return visitType(noType, param);
    }

    @Override
    public R visitCapturedType(final CapturedType capturedType, final P param) {
        return visitType(capturedType, param);
    }
}

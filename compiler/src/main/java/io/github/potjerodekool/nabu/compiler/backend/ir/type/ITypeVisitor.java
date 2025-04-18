package io.github.potjerodekool.nabu.compiler.backend.ir.type;

public interface ITypeVisitor<R,P> {
    R visitIntersectionType(IIntersectionType intersectionType, P param);

    R visitPrimitiveType(IPrimitiveType primitiveType, P param);

    R visitReferenceType(IReferenceType referenceType, P param);

    R visitTypeVariableType(ITypeVariable typeVariable, P param);

    R visitWildcardType(IWildcardType wildcardType, P param);

    R visitArrayType(IArrayType arrayType, P param);
}

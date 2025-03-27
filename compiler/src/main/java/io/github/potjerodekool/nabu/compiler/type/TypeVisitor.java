package io.github.potjerodekool.nabu.compiler.type;

public interface TypeVisitor<R, P> {

    R visitUnknownType(TypeMirror typeMirror,
                       P param);

    default R visitArrayType(ArrayType arrayType,
                             P param) {
        return visitUnknownType(arrayType, param);
    }

    default R visitDeclaredType(DeclaredType declaredType,
                        P param) {
        return visitUnknownType(declaredType, param);
    }

    default R visitMethodType(ExecutableType methodType,
                      P param) {
        return visitUnknownType(methodType, param);
    }

    default R visitNoType(NoType noType,
                  P param) {
        return visitUnknownType(noType, param);
    }

    default R visitPrimitiveType(PrimitiveType primitiveType,
                         P param) {
        return visitUnknownType(primitiveType, param);
    }

    default R visitNullType(NullType nullType, P param) {
        return visitUnknownType(nullType, param);
    }

    default R visitVariableType(VariableType variableType, P param) {
        return visitUnknownType(variableType, param);
    }

    default R visitWildcardType(WildcardType wildcardType, P param) {
        return visitUnknownType(wildcardType, param);
    }

    default R visitTypeVariable(TypeVariable typeVariable, P param) {
        return visitUnknownType(typeVariable, param);
    }

    default R visitIntersectionType(IntersectionType intersectionType, P param) {
        return visitUnknownType(intersectionType, param);
    }

    default R visitNoneType(NoType noType, P param) {
        return visitUnknownType(noType, param);
    }

    default R visitCapturedType(CapturedType capturedType, P param) {
        return visitUnknownType(capturedType, param);
    }

    default R visitType(TypeMirror typeMirror, P param) {
        return visitUnknownType(typeMirror, param);
    }

    default R visitPackageType(PackageType packageType, P param) {
        return visitUnknownType(packageType, param);
    }

    default R visitUnionType(UnionType unionType, P param) {
        return visitUnknownType(unionType, param);
    }
}

package io.github.potjerodekool.nabu.type;

/**
 * A visitor to visit types.
 *
 * @param <R> The result type.
 * @param <P> The parameter type.
 */
public interface TypeVisitor<R, P> {
    /**
     * @param typeMirror A type.
     * @param param      A parameter.
     * @return A result.
     * <p>
     * Called for unknown or unhandled types.
     */
    R visitUnknownType(TypeMirror typeMirror,
                       P param);

    /**
     * @param arrayType An array type.
     * @param param     A parameter.
     * @return Returns a result.
     */
    default R visitArrayType(ArrayType arrayType,
                             P param) {
        return visitUnknownType(arrayType, param);
    }

    /**
     * @param declaredType A declared type.
     * @param param        A parameter.
     * @return Returns a result.
     */
    default R visitDeclaredType(DeclaredType declaredType,
                                P param) {
        return visitUnknownType(declaredType, param);
    }

    /**
     * @param methodType A method type.
     * @param param      A parameter.
     * @return Returns a result.
     */
    default R visitMethodType(ExecutableType methodType,
                              P param) {
        return visitUnknownType(methodType, param);
    }

    /**
     * @param noType A no type.
     * @param param  A parameter.
     * @return Returns a result.
     */
    default R visitNoType(NoType noType,
                          P param) {
        return visitUnknownType(noType, param);
    }

    /**
     * @param primitiveType A primitive type.
     * @param param         A parameter.
     * @return Returns a result.
     */
    default R visitPrimitiveType(PrimitiveType primitiveType,
                                 P param) {
        return visitUnknownType(primitiveType, param);
    }

    /**
     * @param nullType A null type.
     * @param param    A parameter.
     * @return Returns a result.
     */
    default R visitNullType(NullType nullType, P param) {
        return visitUnknownType(nullType, param);
    }

    /**
     * @param variableType A variable type.
     * @param param        A parameter.
     * @return Returns a result.
     */
    default R visitVariableType(VariableType variableType, P param) {
        return visitUnknownType(variableType, param);
    }

    /**
     * @param wildcardType A wildcard type.
     * @param param        A paramter.
     * @return Returns a result.
     */
    default R visitWildcardType(WildcardType wildcardType, P param) {
        return visitUnknownType(wildcardType, param);
    }

    /**
     * @param typeVariable A type variable.
     * @param param        A parameter.
     * @return Returns a result.
     */
    default R visitTypeVariable(TypeVariable typeVariable, P param) {
        return visitUnknownType(typeVariable, param);
    }

    /**
     * @param intersectionType An intersection type.
     * @param param            A paramter.
     * @return Returns a result.
     */
    default R visitIntersectionType(IntersectionType intersectionType, P param) {
        return visitUnknownType(intersectionType, param);
    }

    /**
     * @param capturedType A captured type.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitCapturedType(CapturedType capturedType, P param) {
        return visitUnknownType(capturedType, param);
    }

    /**
     * @param typeMirror A type.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitType(TypeMirror typeMirror, P param) {
        return visitUnknownType(typeMirror, param);
    }

    /**
     * @param packageType  A package type.
     * @param param A parameter.
     * @return Returns a result.
     */

    default R visitPackageType(PackageType packageType, P param) {
        return visitUnknownType(packageType, param);
    }

    /**
     * @param unionType An union type.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitUnionType(UnionType unionType, P param) {
        return visitUnknownType(unionType, param);
    }

    /**
     * @param moduleType A module type.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitModule(ModuleType moduleType, P param) {
        return visitUnknownType(moduleType, param);
    }
}

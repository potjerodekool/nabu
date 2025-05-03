package io.github.potjerodekool.nabu.compiler.util;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;

import java.util.List;

public interface Types {
    TypeElement boxedClass(PrimitiveType p);

    PrimitiveType unboxedType(TypeMirror typeMirror);

    boolean isBoxType(TypeMirror typeMirror);

    TypeMirror getNullType();

    PrimitiveType getPrimitiveType(TypeKind kind);

    DeclaredType getDeclaredType(TypeElement typeElem,
                                 TypeMirror... typeArgs);

    DeclaredType getDeclaredType(DeclaredType enclosing,
                                 TypeElement typeElem, TypeMirror... typeArgs);

    ArrayType getArrayType(TypeMirror componentType);

    boolean isSubType(TypeMirror typeMirrorA,
                      TypeMirror typeMirrorB);

    boolean isAssignable(TypeMirror typeMirrorA,
                         TypeMirror typeMirrorB);

    DeclaredType getErrorType(String className);

    boolean isSameType(TypeMirror typeA, TypeMirror typeB);

    WildcardType getWildcardType(TypeMirror extendsBound,
                                 TypeMirror superBound);

    TypeMirror asMemberOf(DeclaredType containing, Element element);

    TypeMirror erasure(TypeMirror t);

    TypeMirror getIntersectionType(List<TypeMirror> typeBound);

    Element asElement(TypeMirror t);

    boolean contains(TypeMirror t1, TypeMirror t2);

    boolean isSubsignature(ExecutableType m1, ExecutableType m2);

    List<? extends TypeMirror> directSupertypes(TypeMirror t);

    TypeMirror capture(TypeMirror t);

    NoType getNoType(TypeKind kind);


    ExecutableType getExecutableType(ExecutableElement methodSymbol,
                                     List<? extends TypeVariable> typeVariables,
                                     TypeMirror returnType,
                                     List<? extends TypeMirror> argumentTypes,
                                     List<? extends TypeMirror> thrownTypes);

    TypeVariable getTypeVariable(String name,
                                 TypeMirror upperBound,
                                 TypeMirror lowerBound);

    TypeMirror getVariableType(TypeMirror interferedType);

    TypeMirror supertype(TypeMirror type);
}

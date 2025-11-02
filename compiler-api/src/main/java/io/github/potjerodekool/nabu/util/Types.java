package io.github.potjerodekool.nabu.util;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.type.*;

import java.util.List;

/**
 * Utility methods for working on types.
 */
public interface Types {

    /**
     * @param p A primitive type.
     * @return Returns the boxes class of the primitive.
     */
    TypeElement boxedClass(PrimitiveType p);

    /**
     * @param typeMirror A type.
     * @return Returns the primitive type of the given type.
     * or throws an IllegalArgument if the provides type is a box type.
     */
    PrimitiveType unboxedType(TypeMirror typeMirror);

    /**
     * @param typeMirror A type.
     * @return Returns true if the given type is a box type.
     */
    boolean isBoxType(TypeMirror typeMirror);

    /**
     * @return Returns a NullType.
     */
    TypeMirror getNullType();

    /**
     * @param kind a TypeKind.
     * @return Returns the primitive type for the given kind
     * or throws an IllegalArgumentException if the given kind isn't a primitive kind.
     */
    PrimitiveType getPrimitiveType(TypeKind kind);

    /**
     * @param typeElem A type element.
     * @param typeArgs Zero or more type arguments.
     * @return Return a type with the given type arguments.
     * For example of the type element is Set and the typeArgs is String
     * the returned type will be Set&lt;String&gt;
     */
    DeclaredType getDeclaredType(TypeElement typeElem,
                                 TypeMirror... typeArgs);

    /**
     * @param enclosing A enclosing type, may be null.
     * @param typeElem A type element.
     * @param typeArgs Zero or more type arguments.
     * @return Return a type with the given type arguments.
     */
    DeclaredType getDeclaredType(DeclaredType enclosing,
                                 TypeElement typeElem,
                                 TypeMirror... typeArgs);

    /**
     * @param componentType A component type.
     * @return Returns an ArrayType with the given component type.
     * For example if the component type is Integer
     * then it will return Integer[].
     */
    ArrayType getArrayType(TypeMirror componentType);

    /**
     * @param typeMirrorA A type.
     * @param typeMirrorB Another type.
     * @return Returns true if the first type is a subtype of the second type.
     */
    boolean isSubType(TypeMirror typeMirrorA,
                      TypeMirror typeMirrorB);

    /**
     * @param typeMirrorA A type.
     * @param typeMirrorB Another type.
     * @return Returns true if the first type is assignable to the second type.
     * For example Integer is assignable to Number but Number is not assignable to Integer.
     */
    boolean isAssignable(TypeMirror typeMirrorA,
                         TypeMirror typeMirrorB);

    /**
     * @param className A classname.
     * @return Returns an errortype of the given class.
     */
    DeclaredType getErrorType(String className);

    /**
     * @param typeA A type.
     * @param typeB Another type.
     * @return Return true if the first type is the same type of the second type.
     */
    boolean isSameType(TypeMirror typeA,
                       TypeMirror typeB);

    /**
     * @param extendsBound The extends bound.
     * @param superBound The superBound.
     * @return Returns a WildcardType with the given bound.
     * Only extends or super may be given or none.
     * Throws an IllegalArgumentException if both a non null.
     */
    WildcardType getWildcardType(TypeMirror extendsBound,
                                 TypeMirror superBound);

    /**
     * @param containing
     * @param element
     * @return
     */
    TypeMirror asMemberOf(DeclaredType containing,
                          Element element);

    /**
     * @param t
     * @return
     */
    TypeMirror erasure(TypeMirror t);

    /**
     * @param typeBound
     * @return
     */
    TypeMirror getIntersectionType(List<TypeMirror> typeBound);

    /**
     * @param t A type.
     * @return Returns the element of the given type.
     */
    Element asElement(TypeMirror t);

    /**
     * @param t1
     * @param t2
     * @return
     */
    boolean contains(TypeMirror t1,
                     TypeMirror t2);

    /**
     * @param m1
     * @param m2
     * @return
     */
    boolean isSubsignature(ExecutableType m1,
                           ExecutableType m2);

    /**
     * @param t A type.
     * @return Returns the direct super types of the given type,
     * so the direct super class or interfaces.
     */
    List<? extends TypeMirror> directSupertypes(TypeMirror t);

    /**
     * @param t
     * @return
     */
    TypeMirror capture(TypeMirror t);

    /**
     * @param kind A kind.
     * @return Return the NoType of the given kind
     * or throws an IllegalArgumentException if the given kind is not a NoType kind.
     */
    NoType getNoType(TypeKind kind);


    /**
     * @param methodSymbol
     * @param typeVariables
     * @param returnType
     * @param argumentTypes
     * @param thrownTypes
     * @return
     */
    ExecutableType getExecutableType(ExecutableElement methodSymbol,
                                     List<? extends TypeVariable> typeVariables,
                                     TypeMirror returnType,
                                     List<? extends TypeMirror> argumentTypes,
                                     List<? extends TypeMirror> thrownTypes);

    /**
     * @param name
     * @param upperBound
     * @param lowerBound
     * @return
     */
    TypeVariable getTypeVariable(String name,
                                 TypeMirror upperBound,
                                 TypeMirror lowerBound);

    /**
     * @param interferedType
     * @return
     */
    TypeMirror getVariableType(TypeMirror interferedType);

    /**
     * @param type A type.
     * @return Returns the super type of the given type or null if it doesn't have a super type.
     */
    TypeMirror supertype(TypeMirror type);

    /**
     * @param t A type.
     * @return Returns the interfaces of the given type.
     */
    List<? extends TypeMirror> interfaces(TypeMirror t);

    /**
     * @return Returns the type representing java.lang.Object.
     */
    TypeMirror getObjectType();
}

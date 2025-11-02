package io.github.potjerodekool.nabu.type;


import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;

import java.util.List;

/**
 * Root interface of types.
 */
public interface TypeMirror {

    /**
     * @return Returns the kind of the type.
     */
    TypeKind getKind();

    /**
     * @param visitor A visitor.
     * @param param a parameter.
     * @return Returns a result which may be null.
     * @param <R> The result type.
     * @param <P> The type of the param.
     */
    <R, P> R accept(TypeVisitor<R, P> visitor, P param);

    /**
     * @param obj The reference object with which to compare.
     * @return Returns true if this object is equal to the provided object
     *
     * This method is added so all implementations of this class need to implement this method.
     */
    boolean equals(Object obj);

    /**
     * @return Returns if this type is a primitive type or not.
     */
    default boolean isPrimitiveType() {
        return false;
    }

    /**
     * @return Returns if this type is a reference type or not.
     */
    default boolean isReferenceType() {
        return false;
    }

    /**
     * @return Returns if this type is a declared type or not.
     */
    default boolean isDeclaredType() {
        return false;
    }

    /**
     * @return Returns a list of type arguments of this type.
     */
    default List<? extends TypeMirror> getTypeArguments() {
        return List.of();
    }

    /**
     * @return Returns the enclosed type of this type which may be null.
     */
    default TypeMirror getEnclosingType() {
        return null;
    }

    /**
     * @return Returns the parameter types of this type.
     */
    default List<? extends TypeMirror> getParameterTypes() {
        return List.of();
    }

    /**
     * @return Return the return type of this type which may be null.
     * An executable type should not return null, all other types should return null.
     */
    default TypeMirror getReturnType() {
        return null;
    }

    /**
     * @return Returns the receiver type of this type which may be null.
     * An executable type may return a non-null value, all other types should return null.
     */
    default TypeMirror getReceiverType() {
        return null;
    }

    /**
     * @return Returns a list of exception types.
     */
    default List<? extends TypeMirror> getThrownTypes() {
        return List.of();
    }

    /**
     * @return Returns the upper bound of this type which may be null.
     */
    default TypeMirror getUpperBound() {
        return null;
    }

    /**
     * @return Returns the lower bound of this type which may be null.
     */
    default TypeMirror getLowerBound() {
        return null;
    }

    /**
     * @return Returns the element of this type.
     */
    default Element asElement() {
        return null;
    }

    /**
     * @return Returns a type element of this type.
     * A convenience method which allows you to write:
     * final var typeElement = type.asTypeElement();
     * <p> </p>
     * instead of:
     * final var typeElement = (TypeElement) type.asElement();
     */
    default TypeElement asTypeElement() {
        return (TypeElement) asElement();
    }

    /**
     * @return Returns all parameters.
     */
    default List<? extends TypeMirror> getAllParameters() {
        return List.of();
    }

    /**
     * @return Returns true if this is a raw type.
     */
    default boolean isRaw() {
        final var typeElement = asElement();
        return this != typeElement.asType()
                && !typeElement.asType().getAllParameters().isEmpty()
                && getAllParameters().isEmpty();
    }

    /**
     * @return Returns true if this type is parameterized.
     */
    default boolean isParameterized() {
        return false;
    }

    /**
     * @return Returns true if this is a compound type.
     */
    default boolean isCompound() {
        return false;
    }

    /**
     * @return Returns true if this is an interface type.
     */
    default boolean isInterface() {
        return false;
    }

    /**
     * @return Return the class name of this type which may be null.
     */
    String getClassName();

    /**
     * @return Returns true if this type is a type variable.
     */
    default boolean isTypeVariable() {
        return this instanceof TypeVariable;
    }

    /**
     * @return Returns true if this type is an array type.
     */
    default boolean isArrayType() {
        return this instanceof ArrayType;
    }
}

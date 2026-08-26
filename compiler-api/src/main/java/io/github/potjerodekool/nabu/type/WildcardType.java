package io.github.potjerodekool.nabu.type;

/**
 * A wildcard type.
 * <p> </p>
 * Unbound wildcard: ?
 * Wildcard with extends bound: ? extends List
 * Wildcard with super bound: ? super ArrayList
 */
public interface WildcardType extends TypeMirror {

    /**
     * @return Returns the extends bound.
     * Only if the bound kind is EXTENDS it's non-null else null
     */
    TypeMirror getExtendsBound();

    /**
     * @return Returns the super bound.
     * Only if the bound kind is SUPER it's non-null else null
     */
    TypeMirror getSuperBound();

    /**
     * @return Returns the bound.
     * If the bound is EXTENDS it will return the extends bound
     * if the bound is SUPER it will return the super bound
     * else it will return null.
     */
    TypeMirror getBound();

    /**
     * @return Return the bound kind.
     */
    BoundKind getBoundKind();

    /**
     * @return Returns true if the bound is EXTENDS.
     */
    default boolean isExtendsBound() {
        return getBoundKind() == BoundKind.EXTENDS;
    }

    /**
     * @return Returns true if the bound is SUPER.
     */
    default boolean isSuperBound() {
        return getBoundKind() == BoundKind.SUPER;
    }
}

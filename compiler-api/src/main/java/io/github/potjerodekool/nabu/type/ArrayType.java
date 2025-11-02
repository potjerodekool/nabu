package io.github.potjerodekool.nabu.type;

import java.util.List;

/**
 * An array type.
 */
public interface ArrayType extends ReferenceType {

    /**
     * @return Returns the component type of the array.
     */
    TypeMirror getComponentType();

    @Override
    default List<? extends TypeMirror> getAllParameters() {
        return getComponentType().getAllParameters();
    }

    @Override
    default boolean isParameterized() {
        return getComponentType().isParameterized();
    }
}

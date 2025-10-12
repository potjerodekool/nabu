package io.github.potjerodekool.nabu.type;

import java.util.List;

public interface ArrayType extends ReferenceType {
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

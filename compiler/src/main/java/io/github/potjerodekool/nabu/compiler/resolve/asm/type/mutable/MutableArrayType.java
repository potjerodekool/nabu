package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;

import java.util.Map;

public class MutableArrayType extends MutableType {

    private MutableType componentType;

    public MutableArrayType() {
    }

    public MutableArrayType(final MutableType componentType) {
        this.componentType = componentType;
    }

    public void setComponentType(final MutableType componentType) {
        this.componentType = componentType;
    }

    @Override
    public TypeMirror toType(final ClassElementLoader loader, final Map<String, TypeVariable> typeVariablesMap) {
        return loader.getTypes().getArrayType(componentType.toType(loader, typeVariablesMap));
    }

    public MutableType getComponentType() {
        return componentType;
    }
}

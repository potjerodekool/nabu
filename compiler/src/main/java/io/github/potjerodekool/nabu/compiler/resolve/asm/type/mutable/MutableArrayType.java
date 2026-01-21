package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;
import io.github.potjerodekool.nabu.util.Types;

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
    public TypeMirror toType(final Types types,
    final Map<String, TypeVariable> typeVariablesMap) {
        return types.getArrayType(componentType.toType(types, typeVariablesMap));
    }

    public MutableType getComponentType() {
        return componentType;
    }
}

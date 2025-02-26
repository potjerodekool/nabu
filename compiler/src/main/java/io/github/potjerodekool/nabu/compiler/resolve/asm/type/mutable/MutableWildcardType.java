package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;

import java.util.Map;

public class MutableWildcardType extends MutableType {

    private final MutableType extendsBound;
    private final MutableType superBound;

    public MutableWildcardType(final MutableType extendsBound,
                               final MutableType superBound) {
        this.extendsBound = extendsBound;
        this.superBound = superBound;
    }

    @Override
    public TypeMirror toType(final ClassElementLoader loader, final Map<String, TypeVariable> typeVariablesMap) {
        return loader.getTypes().getWildcardType(
                extendsBound != null ? extendsBound.toType(loader, typeVariablesMap) : null,
                superBound != null ? superBound.toType(loader, typeVariablesMap) : null);
    }

}

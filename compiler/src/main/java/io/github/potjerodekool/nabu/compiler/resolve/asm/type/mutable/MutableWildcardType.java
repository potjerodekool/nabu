package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;
import io.github.potjerodekool.nabu.util.Types;

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
    public TypeMirror toType(final Types types,
                             final Map<String, TypeVariable> typeVariablesMap) {
        return types.getWildcardType(
                extendsBound != null ? extendsBound.toType(types, typeVariablesMap) : null,
                superBound != null ? superBound.toType(types, typeVariablesMap) : null);
    }
}

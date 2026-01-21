package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;
import io.github.potjerodekool.nabu.util.Types;

import java.util.Map;

public class MutableTypeVariable extends MutableType {

    private final String name;
    private MutableType upperBound;
    private MutableType lowerBound;

    public MutableTypeVariable(final String name,
                               final MutableType upperBound,
                               final MutableType lowerBound) {
        this.name = name;
        this.upperBound = upperBound;
        this.lowerBound = lowerBound;
    }

    public void setUpperBound(final MutableType upperBound) {
        this.upperBound = upperBound;
    }

    public void setLowerBound(final MutableType lowerBound) {
        this.lowerBound = lowerBound;
    }

    @Override
    public TypeMirror toType(final Types types,
                             final Map<String, TypeVariable> typeVariablesMap) {
        var typeVariable = typeVariablesMap.get(name);

        if (typeVariable == null) {
            typeVariable = types.getTypeVariable(
                    name,
                    upperBound != null ? upperBound.toType(types, typeVariablesMap) : null,
                    lowerBound != null ? lowerBound.toType(types, typeVariablesMap) : null
            );
            typeVariablesMap.put(name, typeVariable);
        }

        return typeVariable;
    }
}

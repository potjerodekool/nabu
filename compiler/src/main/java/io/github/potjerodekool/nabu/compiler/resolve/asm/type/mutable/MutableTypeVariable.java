package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;

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
    public TypeMirror toType(final ClassElementLoader loader, final Map<String, TypeVariable> typeVariablesMap) {
        var typeVariable = typeVariablesMap.get(name);

        if (typeVariable == null) {
            typeVariable = loader.getTypes().getTypeVariable(
                    name,
                    upperBound != null ? upperBound.toType(loader, typeVariablesMap) : null,
                    lowerBound != null ? lowerBound.toType(loader, typeVariablesMap) : null
            );
            typeVariablesMap.put(name, typeVariable);
        }

        return typeVariable;
    }
}

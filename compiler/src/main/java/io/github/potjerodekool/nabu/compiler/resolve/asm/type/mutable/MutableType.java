package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;

import java.util.Map;

public abstract class MutableType {
    public abstract TypeMirror toType(final ClassElementLoader loader, final Map<String, TypeVariable> typeVariablesMap);

}

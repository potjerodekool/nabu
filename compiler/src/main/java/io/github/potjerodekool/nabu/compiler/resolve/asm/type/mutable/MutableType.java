package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;
import io.github.potjerodekool.nabu.util.Types;

import java.util.Map;

public abstract class MutableType {

    public abstract TypeMirror toType(Types types,
                                      Map<String, TypeVariable> typeVariablesMap);

}

package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;

import java.util.List;

public record MethodSignature(List<TypeVariable> typeVariables,
                              TypeMirror returnType,
                              List<TypeMirror> argumentTypes,
                              List<TypeMirror> thrownTypes) {
}

package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.List;

record MethodHeader(List<Parameter> parameters,
                    IType returnType) {
}
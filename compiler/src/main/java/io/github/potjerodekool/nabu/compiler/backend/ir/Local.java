package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.Objects;

public record Local(String name,
                    IType type,
                    int index) {

    public Local {
        Objects.requireNonNull(type);
    }
}

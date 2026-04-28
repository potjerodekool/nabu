package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.ir.types.IRType;

public record LocalVariable(int index,
                            IRType type) {

    public LocalVariable {
        if (index < 0) {
            throw new IllegalArgumentException("Invalid index");
        }
    }

}

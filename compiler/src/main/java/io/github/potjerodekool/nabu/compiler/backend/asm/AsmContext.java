package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.ir.IRGlobal;

public interface AsmContext {
    IRGlobal getGlobal(String name);
}

package io.github.potjerodekool.nabu.compiler.impl;

public interface Factory<T> {

    T create(CompilerContextImpl compilerContext);
}

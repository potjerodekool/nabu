package io.github.potjerodekool.nabu.compiler.internal;

public interface Factory<T> {

    T create(CompilerContextImpl compilerContext);
}

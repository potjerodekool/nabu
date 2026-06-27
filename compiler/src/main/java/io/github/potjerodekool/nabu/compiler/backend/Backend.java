package io.github.potjerodekool.nabu.compiler.backend;

import io.github.potjerodekool.nabu.compiler.ir.IRModule;

import java.nio.file.Path;

public interface Backend {

    void compile(IRModule module, CompileOptions opts, Path output)
            throws CompileException;
}

package io.github.potjerodekool.nabu.backend;

import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.util.CompileException;

import java.nio.file.Path;

public interface Backend {

    void compile(IRModule module, CompileOptions opts, Path output)
            throws CompileException;
}

package io.github.potjerodekool.nabu.backend;

import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.util.CompileException;

import java.nio.file.Path;
import java.util.List;

public interface Backend {

    void compile(IRModule module, CompileOptions opts, Path output)
            throws CompileException;

    /**
     * Compileert meerdere modules in één compilatie. De standaardimplementatie
     * compileert elke module afzonderlijk (behoudt het bestaande gedrag voor
     * backends zonder cross-module kennis). Een backend die object-model-
     * informatie over klassen heen deelt (bv. super-velden, override-slots)
     * kan dit overschrijven met een echte batch-compilatie.
     */
    default void compileAll(List<IRModule> modules, CompileOptions opts, Path output)
            throws CompileException {
        for (IRModule module : modules) {
            compile(module, opts, output);
        }
    }
}

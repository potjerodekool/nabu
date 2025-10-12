package io.github.potjerodekool.nabu.compiler.backend;


import io.github.potjerodekool.nabu.compiler.backend.lower.Lower;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

public final class LowerPhase {

    private LowerPhase() {
    }

    public static CompilationUnit lower(final CompilationUnit compilationUnit,
                                        final CompilerContextImpl compilerContext) {
        final var lower = new Lower(compilerContext);
        lower.process(compilationUnit);
        return compilationUnit;
    }
}

package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ResolverPhase;

public final class ResolvePhase {

    private ResolvePhase() {
    }

    static FileObjectAndCompilationUnit resolvePhase(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit,
                                                     final CompilerContextImpl compilerContext) {
        final var compilationUnit = fileObjectAndCompilationUnit.compilationUnit();

        final var phase2Resolver = new ResolverPhase(
                compilerContext
        );

        compilationUnit.accept(phase2Resolver, null);
        return fileObjectAndCompilationUnit;
    }
}

package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase;

public final class ResolvePhase {

    private ResolvePhase() {
    }

    public static FileObjectAndCompilationUnit resolvePhase(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit,
                                                            final CompilerContextImpl compilerContext) {
        final var compilationUnit = fileObjectAndCompilationUnit.compilationUnit();

        final var resolverPhase = new ResolverPhase(
                compilerContext
        );

        resolverPhase.acceptTree(compilationUnit, null);
        return fileObjectAndCompilationUnit;
    }
}

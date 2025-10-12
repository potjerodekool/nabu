package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;

public final class TransformPhase {

    private TransformPhase() {
    }

    static FileObjectAndCompilationUnit transform(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit,
                                                  final CompilerContextImpl compilerContext) {
        final var compilationUnit = fileObjectAndCompilationUnit.compilationUnit();

        final var codeTransformers = compilerContext.getPluginRegistry()
                .getExtensionManager()
                .getCodeTransformers();

        codeTransformers.forEach(codeTransformer -> codeTransformer.tranform(compilationUnit));
        return fileObjectAndCompilationUnit;
    }


}

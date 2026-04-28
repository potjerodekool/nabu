package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.tree.CompilationUnit;

public final class TransformPhase {

    private TransformPhase() {
    }

    public static CompilationUnit transform(final CompilationUnit compilationUnit,
                                                         final CompilerContextImpl compilerContext) {
                final var codeTransformers = compilerContext.getPluginRegistry()
                .getExtensionManager()
                .getCodeTransformers();

        codeTransformers.forEach(codeTransformer -> codeTransformer.transform(compilationUnit));
        return compilationUnit;
    }


}

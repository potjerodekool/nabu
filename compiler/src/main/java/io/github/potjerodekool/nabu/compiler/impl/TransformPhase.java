package io.github.potjerodekool.nabu.compiler.impl;

public final class TransformPhase {

    private TransformPhase() {
    }

    public static FileObjectAndCompilationUnit transform(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit,
                                                         final CompilerContextImpl compilerContext) {
        final var compilationUnit = fileObjectAndCompilationUnit.compilationUnit();

        final var codeTransformers = compilerContext.getPluginRegistry()
                .getExtensionManager()
                .getCodeTransformers();

        codeTransformers.forEach(codeTransformer -> codeTransformer.transform(compilationUnit));
        return fileObjectAndCompilationUnit;
    }


}

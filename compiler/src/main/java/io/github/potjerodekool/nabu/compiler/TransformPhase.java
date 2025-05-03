package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.nabu.compiler.transform.CodeTransformer;

public final class TransformPhase {

    private TransformPhase() {
    }

    static FileObjectAndCompilationUnit transform(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit,
                                                   final ApplicationContext applicationContext) {
        final var compilationUnit = fileObjectAndCompilationUnit.compilationUnit();
        final var codeTransformers = applicationContext.getBeansOfType(CodeTransformer.class);
        codeTransformers.forEach(codeTransformer -> codeTransformer.tranform(compilationUnit));
        return fileObjectAndCompilationUnit;
    }


}

package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;

public class AnnotatePhase extends AbstractTreeVisitor<Object, CompilerContext> {

    public static FileObjectAndCompilationUnit annotate(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit,
                                                        final CompilerContext compilerContext) {
        final var phase = new AnnotatePhase();
        phase.acceptTree(fileObjectAndCompilationUnit.compilationUnit(), compilerContext);
        return fileObjectAndCompilationUnit;
    }
}

package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

public class AnnotatePhase extends AbstractTreeVisitor<Object, CompilerContext> {

    public static CompilationUnit annotate(final CompilationUnit compilationUnit,
                                           final CompilerContext compilerContext) {
        final var phase = new AnnotatePhase();
        phase.acceptTree(compilationUnit, compilerContext);
        return compilationUnit;
    }
}

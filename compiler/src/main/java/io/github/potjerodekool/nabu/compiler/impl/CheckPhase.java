package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.resolve.impl.Checker;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tree.CompilationUnit;


public final class CheckPhase {

    private CheckPhase() {
    }

    public static CompilationUnit check(final CompilationUnit compilationUnit,
                                        final CompilerContext compilerContext,
                                        final ErrorCapture errorCapture) {
        final var checker = new Checker(compilerContext, errorCapture);
        compilationUnit.accept(checker, null);
        return compilationUnit;
    }
}

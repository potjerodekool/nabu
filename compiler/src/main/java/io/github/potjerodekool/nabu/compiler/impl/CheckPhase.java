package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.resolve.impl.Checker;
import io.github.potjerodekool.nabu.tree.CompilationUnit;


public final class CheckPhase {

    private CheckPhase() {
    }

    static CompilationUnit check(final CompilationUnit compilationUnit,
                                 final ErrorCapture errorCapture) {
        final var checker = new Checker(errorCapture);
        compilationUnit.accept(checker, null);
        return compilationUnit;
    }
}

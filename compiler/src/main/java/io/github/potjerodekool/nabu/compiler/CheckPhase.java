package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.resolve.Checker;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

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

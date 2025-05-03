package io.github.potjerodekool.nabu.compiler.backend;

import io.github.potjerodekool.nabu.compiler.backend.ir.Translate;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

public final class IRPhase {

    private IRPhase() {
    }

    public static CompilationUnit ir(final CompilationUnit compilationUnit) {
        final var translate = new Translate();
        compilationUnit.accept(translate, null);
        return compilationUnit;
    }

}

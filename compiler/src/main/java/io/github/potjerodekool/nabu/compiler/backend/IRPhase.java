package io.github.potjerodekool.nabu.compiler.backend;

import io.github.potjerodekool.nabu.compiler.backend.ir.impl.Translate;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

public final class IRPhase {

    private IRPhase() {
    }

    public static CompilationUnit ir(final CompilerContextImpl compilerContext,
                                     final CompilationUnit compilationUnit) {
        final var translate = new Translate(compilerContext);
        translate.acceptTree(compilationUnit, null);
        return compilationUnit;
    }

}

package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.resolve.impl.Checker;
import io.github.potjerodekool.nabu.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tree.CompilationUnit;


public final class CheckPhase {

    private CheckPhase() {
    }

    public static CompilationUnit check(final CompilationUnit compilationUnit,
                                        final CompilerContext compilerContext,
                                        final CompilerDiagnosticListener compilerDiagnosticListener) {
        final var checker = new Checker(compilerContext, compilerDiagnosticListener);
        final var globalScope = new GlobalScope(compilationUnit, null);
        checker.acceptTree(compilationUnit, globalScope);
        return compilationUnit;
    }
}

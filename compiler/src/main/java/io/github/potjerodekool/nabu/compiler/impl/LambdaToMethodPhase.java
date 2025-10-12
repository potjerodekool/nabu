package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda.LambdaToMethod;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

public final class LambdaToMethodPhase {

    private LambdaToMethodPhase() {
    }

    static CompilationUnit lambdaToMethod(final CompilationUnit compilationUnit) {
        final var lamdaToMethod = new LambdaToMethod();
        compilationUnit.accept(lamdaToMethod, null);
        return compilationUnit;
    }

}

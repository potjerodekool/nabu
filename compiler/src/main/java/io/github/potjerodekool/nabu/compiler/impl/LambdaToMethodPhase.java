package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda.LambdaToMethod;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

public final class LambdaToMethodPhase {

    private LambdaToMethodPhase() {
    }

    public static CompilationUnit lambdaToMethod(final CompilationUnit compilationUnit) {
        final var lamdaToMethod = new LambdaToMethod();
        lamdaToMethod.acceptTree(compilationUnit, null);
        return compilationUnit;
    }

}

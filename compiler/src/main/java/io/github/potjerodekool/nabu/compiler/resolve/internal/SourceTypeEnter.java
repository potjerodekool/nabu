package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;

public class SourceTypeEnter {

    private final CompilerContextImpl compilerContext;

    SourceTypeEnter(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
    }

    public void fill(final ClassSymbol classSymbol) {
        compilerContext.getTypeEnter().complete(classSymbol);
    }
}


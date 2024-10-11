package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

import java.util.Objects;

public class GlobalScope implements Scope {

    private final CompilationUnit compilationUnit;

    public GlobalScope(final CompilationUnit compilationUnit) {
        Objects.requireNonNull(compilationUnit);
        this.compilationUnit = compilationUnit;
    }

    @Override
    public GlobalScope getGlobalScope() {
        return this;
    }

    @Override
    public void define(final Element element) {
    }

    @Override
    public Element resolve(final String name) {
        return null;
    }

    @Override
    public CompilationUnit getCompilationUnit() {
        return compilationUnit;
    }
}

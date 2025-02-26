package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

public class GlobalScope implements Scope {

    private final CompilationUnit compilationUnit;
    private final CompilerContext compilerContext;
    private PackageElement packageElement;

    public GlobalScope(final CompilationUnit compilationUnit,
                       final CompilerContext compilerContext) {
        this.compilationUnit = compilationUnit;
        this.compilerContext = compilerContext;
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

    public CompilerContext getCompilerContext() {
        return compilerContext;
    }

    @Override
    public PackageElement getPackageElement() {
        return packageElement;
    }

    @Override
    public void setPackageElement(final PackageElement packageElement) {
        this.packageElement = packageElement;
    }
}

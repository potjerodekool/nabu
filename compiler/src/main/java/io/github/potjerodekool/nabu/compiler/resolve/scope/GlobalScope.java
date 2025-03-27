package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ModuleElement;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.symbol.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.impl.CCompilationTreeUnit;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

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
        final var members = getMembers();

        if (members != null) {
            return members.resolve(name);
        } else {
            return null;
        }
    }

    private WritableScope getMembers() {
        if (packageElement instanceof PackageSymbol packageSymbol) {
            return packageSymbol.getMembers();
        } else {
            return null;
        }
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

    @Override
    public ModuleElement findModuleElement() {
        final var cu = (CCompilationTreeUnit) getCompilationUnit();
        return cu.getModuleSymbol();
    }

    @Override
    public TypeMirror resolveType(final String name) {
        var type = compilationUnit.getNamedImportScope().resolveType(name);

        if (type != null) {
            return type;
        }

        final var members = getMembers();

        if (members == null) {
            return null;
        } else {
            return members.resolveType(name);
        }
    }
}

package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

import java.util.Set;

public interface Scope {

    default Scope getParent() {
        return null;
    }

    default GlobalScope getGlobalScope() {
        final var parent = getParent();
        return parent != null
                ? parent.getGlobalScope()
                : null;
    }

    default CompilationUnit getCompilationUnit() {
        return getGlobalScope().getCompilationUnit();
    }

    void define(Element element);

    Element resolve(String name);

    default Set<String> locals() {
        return Set.of();
    }

    default MethodSymbol getCurrentMethod() {
        final var parent = getParent();
        return parent != null
                ? parent.getCurrentMethod()
                : null;
    }

    default ClassSymbol getCurrentClass() {
        final var parent = getParent();
        return parent != null
                ? parent.getCurrentClass()
                : null;
    }

}

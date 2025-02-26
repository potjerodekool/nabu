package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
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

    default ExecutableElement getCurrentMethod() {
        final var parent = getParent();
        return parent != null
                ? parent.getCurrentMethod()
                : null;
    }

    default TypeElement getCurrentClass() {
        final var parent = getParent();
        return parent != null
                ? parent.getCurrentClass()
                : null;
    }

    default PackageElement getPackageElement() {
        final var parent = getParent();
        return parent != null
                ? parent.getPackageElement()
                : null;
    }

    default void setPackageElement(final PackageElement packageElement) {
        final var parent = getParent();
        if (parent != null) {
            parent.setPackageElement(packageElement);
        }
    }
}

package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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

    default Collection<? extends Element> elements() {
        return List.of();
    }

    void define(Element element);

    default Element resolve(String name) {
        final var parent = getParent();
        return parent != null
                ? parent.resolve(name)
                : null;
    }

    default Element resolve(String name,
                            Predicate<Element> filter) {
        return resolve(name);
    }

    default Iterable<Element> resolveByName(final String name,
                                            final Predicate<Element> filter) {
        final var parent = getParent();

        if (parent != null) {
            return parent.resolveByName(name, filter);
        }

        return List.of();
    }

    default TypeMirror resolveType(String name) {
        final var parent = getParent();
        return parent != null
                ? parent.resolveType(name)
                : null;
    }

    default Set<String> locals() {
        return Set.of();
    }

    default Element getCurrentElement() {
        return null;
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

    default ModuleElement findModuleElement() {
        return findModuleElement(getCurrentClass());
    }

    private ModuleElement findModuleElement(final Element element) {
        if (element == null) {
            final var parent = getParent();
            return parent != null
                    ? parent.findModuleElement()
                    : null;
        } else if (element instanceof PackageElement packageElement) {
            return packageElement.getModuleSymbol();
        } else {
            return findModuleElement(element.getEnclosingElement());
        }
    }
}

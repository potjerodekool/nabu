package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A scope for searching.
 */
public interface Scope {

    /**
     * @return Returns the parent scope or null if there isn't one.
     */
    default Scope getParent() {
        return null;
    }

    /**
     * @return Returns the global scope or null if not found.
     */
    default GlobalScope getGlobalScope() {
        final var parent = getParent();
        return parent != null
                ? parent.getGlobalScope()
                : null;
    }

    /**
     * @return Returns the compilation unit of the scope or null if not found.
     */
    default CompilationUnit getCompilationUnit() {
        final var globalScope = getGlobalScope();
        return globalScope != null ? globalScope.getCompilationUnit() : null;
    }

    /**
     * @return Returns a collection of elements defined in this scope.
     */
    default Collection<? extends Element> elements() {
        return List.of();
    }

    /**
     * @param element An element.
     * Add an element to the scope.
     */
    void define(Element element);

    /**
     * @param name An element name.
     * @return Returns the resolved element with the given name or null if not found.
     */
    default Element resolve(String name) {
        final var parent = getParent();
        return parent != null
                ? parent.resolve(name)
                : null;
    }

    /**
     * @param name An element name.
     * @param filter A filter to use.
     * @return Return the resolved element or null if not found.
     */
    default Element resolve(String name,
                            Predicate<Element> filter) {
        return resolve(name);
    }

    /**
     * @param name An element name.
     * @param filter A filter to use.
     * @return Returns an iterable with the resolved elements.
     */
    default Iterable<Element> resolveByName(final String name,
                                            final Predicate<Element> filter) {
        final var parent = getParent();

        if (parent != null) {
            return parent.resolveByName(name, filter);
        }

        return List.of();
    }

    /**
     * @param name An element name.
     * @return Return the resolved type or null if not found.
     */
    default TypeMirror resolveType(String name) {
        final var parent = getParent();
        return parent != null
                ? parent.resolveType(name)
                : null;
    }

    /**
     * @return Returns a set of local names.
     */
    default Set<String> locals() {
        return Set.of();
    }

    /**
     * @return Return the current element of the scope or null.
     */
    default Element getCurrentElement() {
        return null;
    }

    /**
     * @return Returns the current method of the scope of null.
     */
    default ExecutableElement getCurrentMethod() {
        final var parent = getParent();
        return parent != null
                ? parent.getCurrentMethod()
                : null;
    }

    /**
     * @return Returns the current class of the scope or null.
     */
    default TypeElement getCurrentClass() {
        final var parent = getParent();
        return parent != null
                ? parent.getCurrentClass()
                : null;
    }

    /**
     * @return Returns the package element of this scope or null.
     */
    default PackageElement getPackageElement() {
        final var parent = getParent();
        return parent != null
                ? parent.getPackageElement()
                : null;
    }

    /**
     * @param packageElement Package element to set on this scope.
     */
    default void setPackageElement(final PackageElement packageElement) {
        final var parent = getParent();
        if (parent != null) {
            parent.setPackageElement(packageElement);
        }
    }

    /**
     * @return Returns the module element of this scope or null.
     */
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

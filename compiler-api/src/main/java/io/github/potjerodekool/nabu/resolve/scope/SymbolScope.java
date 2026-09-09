package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;

import java.util.Objects;
import java.util.Optional;

/**
 * A scope for searching both instance as static fields.
 */
public class SymbolScope implements Scope {

    private final DeclaredType declaredType;

    private final Scope parentScope;

    public SymbolScope(final DeclaredType declaredType,
                       final Scope parentScope) {
        Objects.requireNonNull(declaredType);
        this.declaredType = declaredType;
        this.parentScope = parentScope;
    }

    @Override
    public void define(final Element element) {
    }

    @Override
    public Element resolve(final String name) {
        final var symbolResolverOptional = findSymbolResolver(declaredType);

        if (symbolResolverOptional.isPresent()) {
            final var symbolResolver = symbolResolverOptional.get();
            final var element = symbolResolver.resolve(name, declaredType);

            if (element != null) {
                return element;
            }
        }

        var currentClass = getCurrentClass();

        while (currentClass != null) {
            final var fieldOptional = ElementFilter.elements(
                            currentClass,
                            element ->
                                    element.getKind() == ElementKind.FIELD
                                            || element.getKind() == ElementKind.ENUM_CONSTANT,
                            VariableElement.class
                    ).stream()
                    .filter(elem -> elem.getKind() == ElementKind.FIELD
                            || elem.getKind() == ElementKind.ENUM_CONSTANT)
                    .filter(elem -> elem.getSimpleName().equals(name))
                    .findFirst();

            if (fieldOptional.isPresent()) {
                return fieldOptional.get();
            }

            final var superclass = currentClass.getSuperclass();

            if (superclass instanceof DeclaredType superType) {
                currentClass = (TypeElement) superType.asElement();
            } else {
                currentClass = null;
            }
        }

        return parentScope != null ? parentScope.resolve(name) : null;
    }

    private Optional<ElementResolver> findSymbolResolver(final TypeMirror searchType) {
        final var globalScope = getGlobalScope();

        if (globalScope == null) {
            return Optional.empty();
        }

        final var compilerContext = globalScope.getCompilerContext();
        return compilerContext.findSymbolResolver(
                searchType,
                globalScope
        );
    }

    @Override
    public TypeMirror resolveType(final String name) {
        final var typeVariable = declaredType.getTypeArguments().stream()
                .filter(it -> it instanceof TypeVariable)
                .filter(it -> it.asElement().getSimpleName().equals(name))
                .map(it -> (TypeMirror) it)
                .findFirst();

        if (typeVariable.isPresent()) {
            return typeVariable.get();
        }

        var enclosing = getCurrentClass();

        while (enclosing != null) {
            var ancestor = enclosing;

            while (ancestor != null) {
                final var found = findMemberType(ancestor, name);

                if (found.isPresent()) {
                    return found.get().asType();
                }

                final var superclass = ancestor.getSuperclass();

                if (superclass instanceof DeclaredType superType) {
                    ancestor = (TypeElement) superType.asElement();
                } else {
                    ancestor = null;
                }
            }

            final var outerClass = enclosing.getEnclosingElement();

            if (outerClass instanceof TypeElement enclosingType) {
                enclosing = enclosingType;
            } else {
                enclosing = null;
            }
        }

        return Scope.super.resolveType(name);
    }

    private java.util.Optional<TypeElement> findMemberType(final TypeElement classSymbol,
                                                           final String name) {
        return ElementFilter.elements(
                        classSymbol,
                        element ->
                                element.getKind().isClass()
                                        || element.getKind().isInterface()
                                        || element.getKind() == ElementKind.ENUM
                                        || element.getKind() == ElementKind.ANNOTATION_TYPE,
                        TypeElement.class
                ).stream()
                .filter(elem -> elem.getSimpleName().contentEquals(name))
                .findFirst();
    }

    @Override
    public Scope getParent() {
        return parentScope;
    }

    @Override
    public TypeElement getCurrentClass() {
        return declaredType.asTypeElement();
    }
}

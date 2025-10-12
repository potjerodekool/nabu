package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.Optional;

public class SymbolScope implements Scope {

    private final DeclaredType declaredType;

    private final Scope parentScope;

    public SymbolScope(final DeclaredType declaredType,
                       final Scope parentScope) {
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

        final var classSymbol = getCurrentClass();

        final var fieldOptional = ElementFilter.elements(
                        classSymbol,
                        element ->
                                element.getKind() == ElementKind.FIELD
                                        || element.getKind() == ElementKind.ENUM_CONSTANT,
                        VariableElement.class
                ).stream()
                .filter(elem -> elem.getKind() == ElementKind.FIELD)
                .filter(elem -> elem.getSimpleName().equals(name))
                .findFirst();

        if (fieldOptional.isPresent()) {
            return fieldOptional.get();
        } else if (parentScope != null) {
            return parentScope.resolve(name);
        } else {
            return null;
        }
    }

    private Optional<ElementResolver> findSymbolResolver(final TypeMirror searchType) {
        final var globalScope = getGlobalScope();
        final var compilerContext = globalScope.getCompilerContext();
        return compilerContext.findSymbolResolver(
                searchType,
                globalScope
        );
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

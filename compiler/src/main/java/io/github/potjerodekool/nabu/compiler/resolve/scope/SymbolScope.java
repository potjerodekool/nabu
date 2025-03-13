package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.ElementFilter;
import io.github.potjerodekool.nabu.compiler.resolve.SymbolResolver;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

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

        final var fieldOptional = ElementFilter.fields(classSymbol).stream()
                .filter(elem -> elem.getKind() == ElementKind.FIELD)
                .filter(elem -> elem.getSimpleName().equals(name))
                .findFirst();

        if (fieldOptional.isPresent()) {
            return fieldOptional.get();
        } else {
            return parentScope.resolve(name);
        }
    }

    private Optional<SymbolResolver> findSymbolResolver(final TypeMirror searchType) {
        final var globalScope = getGlobalScope();
        final var compilerContext = globalScope.getCompilerContext();
        final var resolverRegistry = compilerContext.getResolverRegistry();
        return resolverRegistry.findSymbolResolver(searchType);
    }

    @Override
    public Scope getParent() {
        return parentScope;
    }

    @Override
    public TypeElement getCurrentClass() {
        return declaredType.getTypeElement();
    }
}

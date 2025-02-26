package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.*;

public class SymbolResolverRegistry {

    private final List<SymbolResolver> resolvers = new ArrayList<>();

    private final SymbolResolver standardResolver = new StandardSymbolResolver();

    public SymbolResolverRegistry(final Set<SymbolResolver> symbolResolvers) {
        this.resolvers.addAll(symbolResolvers);
    }

    public Optional<SymbolResolver> findSymbolResolver(final TypeMirror searchType) {
        final var resolverOptional = resolvers.stream()
                .filter(resolver -> resolver.supports(searchType))
                .findFirst();

        if (resolverOptional.isPresent()) {
            return resolverOptional;
        } else {
            return Optional.of(standardResolver);
        }
    }
}

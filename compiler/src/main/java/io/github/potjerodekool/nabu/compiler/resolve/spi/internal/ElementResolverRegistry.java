package io.github.potjerodekool.nabu.compiler.resolve.spi.internal;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.*;

public class ElementResolverRegistry {

    private final List<ElementResolver> resolvers = new ArrayList<>();

    private final ElementResolver standardResolver = new StandardElementResolver();

    private final ApplicationContext applicationContext;

    private boolean initResolvers = true;

    public ElementResolverRegistry(final ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    private void initResolvers() {
        if (initResolvers) {
            this.initResolvers = false;
            this.resolvers.addAll(applicationContext.getBeansOfType(ElementResolver.class));
        }
    }

    public Optional<ElementResolver> findSymbolResolver(final TypeMirror searchType,
                                                        final CompilerContext compilerContext,
                                                        final Scope scope) {
        initResolvers();
        final var resolverOptional = resolvers.stream()
                .filter(resolver -> resolver.supports(searchType, compilerContext, scope))
                .findFirst();

        if (resolverOptional.isPresent()) {
            return resolverOptional;
        } else {
            return Optional.of(standardResolver);
        }
    }
}

package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.nabu.compiler.resolve.*;

public class CompilerContext {

    private final ClassElementLoader classElementLoader;
    private final MethodResolver methodResolver;
    private final ArgumentBoxer argumentBoxer;
    private final SymbolResolverRegistry resolverRegistry;

    public CompilerContext(final ClassElementLoader classElementLoader,
                           final ApplicationContext applicationContext) {
        this.classElementLoader = classElementLoader;
        this.methodResolver = new MethodResolver(
                classElementLoader.getTypes()
        );
        this.resolverRegistry = createSymbolResolverRegistry(applicationContext);

        this.argumentBoxer = new ArgumentBoxer(
                classElementLoader,
                methodResolver
        );
    }

    private SymbolResolverRegistry createSymbolResolverRegistry(final ApplicationContext applicationContext) {
        final var symbolResolvers = applicationContext.getBeansOfType(SymbolResolver.class);
        return new SymbolResolverRegistry(symbolResolvers);
    }

    public SymbolResolverRegistry getResolverRegistry() {
        return resolverRegistry;
    }

    public ClassElementLoader getClassElementLoader() {
        return classElementLoader;
    }

    public MethodResolver getMethodResolver() {
        return methodResolver;
    }

    public ArgumentBoxer getArgumentBoxer() {
        return argumentBoxer;
    }
}

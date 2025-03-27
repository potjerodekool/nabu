package io.github.potjerodekool.nabu.compiler.internal;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.symbol.module.Modules;
import io.github.potjerodekool.nabu.compiler.io.FileManager;
import io.github.potjerodekool.nabu.compiler.io.StandardLocation;
import io.github.potjerodekool.nabu.compiler.resolve.*;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ClassFinder;
import io.github.potjerodekool.nabu.compiler.resolve.internal.SymbolTable;
import io.github.potjerodekool.nabu.compiler.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.compiler.resolve.spi.internal.ElementResolverRegistry;
import io.github.potjerodekool.nabu.compiler.util.Elements;
import io.github.potjerodekool.nabu.compiler.util.impl.ElementsImpl;

public class CompilerContextImpl implements CompilerContext {

    private final ClassSymbolLoader classElementLoader;
    private final Elements elements;
    private final MethodResolver methodResolver;
    private final ArgumentBoxer argumentBoxer;
    private final ElementResolverRegistry resolverRegistry;

    public CompilerContextImpl(final ApplicationContext applicationContext,
                               final FileManager fileManager) {
        final var symbolTable = new SymbolTable();
        symbolTable.getUnnamedModule().setSourceLocation(StandardLocation.SOURCE_PATH);
        symbolTable.getUnnamedModule().setClassLocation(StandardLocation.CLASS_PATH);


        this.classElementLoader = new AsmClassElementLoader(symbolTable);
        final var classFinder = new ClassFinder(
                symbolTable,
                fileManager,
                classElementLoader
        );

        final var modules = new Modules(
                symbolTable,
                fileManager,
                classElementLoader
        );
        symbolTable.init(modules, classFinder);

        this.elements = new ElementsImpl(
                symbolTable,
                classElementLoader.getTypes(),
                modules
        );

        this.methodResolver = new MethodResolver(
                classElementLoader.getTypes()
        );
        this.resolverRegistry = createSymbolResolverRegistry(applicationContext);

        this.argumentBoxer = new ArgumentBoxer(
                classElementLoader,
                methodResolver
        );
    }

    private ElementResolverRegistry createSymbolResolverRegistry(final ApplicationContext applicationContext) {
        final var symbolResolvers = applicationContext.getBeansOfType(ElementResolver.class);
        return new ElementResolverRegistry(symbolResolvers);
    }

    public ElementResolverRegistry getResolverRegistry() {
        return resolverRegistry;
    }

    @Override
    public ClassSymbolLoader getClassElementLoader() {
        return classElementLoader;
    }

    @Override
    public Elements getElements() {
        return elements;
    }

    @Override
    public MethodResolver getMethodResolver() {
        return methodResolver;
    }

    public ArgumentBoxer getArgumentBoxer() {
        return argumentBoxer;
    }

    @Override
    public void close() throws Exception {
        this.classElementLoader.close();
    }
}

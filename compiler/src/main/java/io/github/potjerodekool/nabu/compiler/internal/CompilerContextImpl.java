package io.github.potjerodekool.nabu.compiler.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ElementBuildersImpl;
import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.lang.model.element.builder.ElementBuilders;
import io.github.potjerodekool.nabu.resolve.ArgumentBoxer;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.compiler.impl.EnumUsageMap;
import io.github.potjerodekool.nabu.compiler.ast.symbol.module.impl.Modules;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuCFileManager;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.resolve.impl.ArgumentBoxerImpl;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ClassFinder;
import io.github.potjerodekool.nabu.compiler.resolve.internal.SymbolGenerator;
import io.github.potjerodekool.nabu.compiler.resolve.internal.TypeEnter;
import io.github.potjerodekool.nabu.compiler.resolve.method.impl.MethodResolverImpl;
import io.github.potjerodekool.nabu.compiler.util.impl.ElementsImpl;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Elements;

import java.util.Optional;

public class CompilerContextImpl implements CompilerContext {

    private final ClassSymbolLoader classElementLoader;
    private final Elements elements;
    private final MethodResolver methodResolver;
    private final ArgumentBoxer argumentBoxer;
    private final TypeEnter typeEnter;
    private final SymbolGenerator symbolGenerator;
    private final EnumUsageMap enumUsageMap = new EnumUsageMap();
    private final PluginRegistry pluginRegistry;

    public CompilerContextImpl(final NabuCFileManager fileManager,
                               final PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;

        final var symbolTable = new SymbolTable();
        symbolTable.getUnnamedModule().setSourceLocation(StandardLocation.SOURCE_PATH);
        symbolTable.getUnnamedModule().setClassLocation(StandardLocation.CLASS_PATH);

        this.classElementLoader = new AsmClassElementLoader(symbolTable);

        final ClassFinder classFinder = new ClassFinder(
                symbolTable,
                fileManager,
                classElementLoader,
                this
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

        this.typeEnter = new TypeEnter(this);

        this.methodResolver = new MethodResolverImpl(
                classElementLoader.getTypes()
        );

        this.argumentBoxer = new ArgumentBoxerImpl(
                classElementLoader,
                methodResolver
        );

        this.symbolGenerator = new SymbolGenerator(this);
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

    public TypeEnter getTypeEnter() {
        return typeEnter;
    }

    public EnumUsageMap getEnumUsageMap() {
        return enumUsageMap;
    }

    public SymbolGenerator getSymbolGenerator() {
        return symbolGenerator;
    }

    @Override
    public Optional<ElementResolver> findSymbolResolver(final TypeMirror searchType,
                                                        final Scope scope) {
        return this.pluginRegistry.getExtensionManager()
                .findSymbolResolver(searchType, scope);
    }

    public SymbolTable getSymbolTable() {
        return classElementLoader.getSymbolTable();
    }

    @Override
    public ElementBuilders getElementBuilders() {
        return ElementBuildersImpl.getInstance();
    }

    public PluginRegistry getPluginRegistry() {
        return pluginRegistry;
    }
}

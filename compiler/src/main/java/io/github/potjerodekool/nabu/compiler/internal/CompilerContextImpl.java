package io.github.potjerodekool.nabu.compiler.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ElementBuildersImpl;
import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuCFileManager;
import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.lang.model.element.builder.ElementBuilders;
import io.github.potjerodekool.nabu.resolve.ArgumentBoxer;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.compiler.impl.EnumUsageMap;
import io.github.potjerodekool.nabu.tools.FileManager;
import io.github.potjerodekool.nabu.compiler.resolve.impl.ArgumentBoxerImpl;
import io.github.potjerodekool.nabu.compiler.resolve.internal.SymbolGenerator;
import io.github.potjerodekool.nabu.compiler.resolve.internal.TypeEnter;
import io.github.potjerodekool.nabu.compiler.resolve.method.impl.MethodResolverImpl;
import io.github.potjerodekool.nabu.compiler.util.impl.ElementsImpl;
import io.github.potjerodekool.nabu.tree.TreeUtils;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Elements;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CompilerContextImpl implements CompilerContext {

    public static class Key<T> {}


    private final Logger logger = Logger.getLogger(CompilerContextImpl.class.getName());
    private final NabuCFileManager fileManager;
    private final Elements elements;
    private final MethodResolver methodResolver;
    private final ArgumentBoxer argumentBoxer;
    private final SymbolGenerator symbolGenerator;
    private final EnumUsageMap enumUsageMap = new EnumUsageMap();
    private final PluginRegistry pluginRegistry;

    private final Map<Key<?>, Object> data = new HashMap<>();
    private final Map<Class<?>, Key<?>> keyTable = new HashMap<>();

    public CompilerContextImpl(final NabuCFileManager fileManager) {
        this(fileManager, new PluginRegistry(), AsmClassElementLoader::new);
    }

    public CompilerContextImpl(final NabuCFileManager fileManager,
                               final PluginRegistry pluginRegistry,
                               final Factory<ClassElementLoader> loaderFactory) {
        this.fileManager = fileManager;
        this.pluginRegistry = pluginRegistry;
        put(FileManager.class, fileManager);
        put(ClassElementLoader.class, loaderFactory);
        put(TypeEnter.class, (Factory<TypeEnter>) TypeEnter::new);

        pluginRegistry.registerPlugins(this);
        fileManager.initialize(pluginRegistry);

        this.elements = new ElementsImpl(this);

        final var classElementLoader = getClassElementLoader();

        this.methodResolver = new MethodResolverImpl(
                classElementLoader.getTypes()
        );

        this.argumentBoxer = new ArgumentBoxerImpl(
                classElementLoader,
                methodResolver
        );

        this.symbolGenerator = new SymbolGenerator(this);
    }

    public <T> T get(final Key<T> key) {
        var value = this.data.get(key);

        if (value instanceof Factory<?> factory) {
            value = factory.create(this);
            this.data.put(key, value);
        }

        return (T) value;
    }

    public <T> void put(final Key<T> key,
                        final T data) {
        this.data.put(key, data);
    }

    public <T> void put(final Key<T> key,
                        final Factory<T> data) {
        this.data.put(key, data);
    }

    public <T> T get(final Class<T> classKey) {
        final var key = toKey(classKey);
        return get(key);
    }

    public <T> void put(final Class<T> type,
                        final T data) {
        final var key = toKey(type);
        put(key, data);
    }

    public <T> void put(final Class<T> type,
                        final Factory<T> data) {
        final var key = toKey(type);
        put(key, data);
    }

    private <T> Key<T> toKey(final Class<T> clazz) {
        Key<T> key = (Key<T>) this.keyTable.get(clazz);

        if (key == null) {
            key = new Key<>();
            this.keyTable.put(clazz, key);
        }

        return key;
    }

    public NabuCFileManager getFileManager() {
        return fileManager;
    }

    public SymbolTable getSymbolTable() {
        return SymbolTable.getInstance(this);
    }

    @Override
    public ClassElementLoader getClassElementLoader() {
        return get(ClassElementLoader.class);
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
    public void close() {
        close(this.fileManager);
        close(this.getClassElementLoader());
    }

    private void close(final AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (final Exception e) {
            logger.log(LogLevel.ERROR, "Failed to close " + closeable.getClass().getName(), e);
        }
    }

    public TypeEnter getTypeEnter() {
        return get(TypeEnter.class);
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

    @Override
    public ElementBuilders getElementBuilders() {
        return ElementBuildersImpl.getInstance();
    }

    @Override
    public TreeUtils getTreeUtils() {
        return new TreeUtils(getClassElementLoader().getTypes());
    }

    public PluginRegistry getPluginRegistry() {
        return pluginRegistry;
    }
}

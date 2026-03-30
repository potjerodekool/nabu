package io.github.potjerodekool.nabu.compiler.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SharedLoader extends ClassLoader {

    private final List<PluginClassLoader> pluginLoaders = new ArrayList<>();

    public SharedLoader() {
        super(SharedLoader.class.getClassLoader());
    }

    public void addPluginClassLoader(final PluginClassLoader pluginClassLoader) {
        pluginLoaders.add(pluginClassLoader);
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        final var classOptional = pluginLoaders.stream()
                .map(loader -> loadClass(name, loader))
                .filter(Objects::nonNull)
                .findFirst();

        if (classOptional.isPresent()) {
            return classOptional.get();
        }

        return super.findClass(name);
    }

    private static Class<?> loadClass(final String name,
                                      final PluginClassLoader loader) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}

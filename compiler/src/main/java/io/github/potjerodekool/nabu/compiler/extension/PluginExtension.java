package io.github.potjerodekool.nabu.compiler.extension;

import io.github.potjerodekool.nabu.tools.CompilerContext;

import java.util.Map;

public class PluginExtension {

    private final Map<String, String> attributes;

    private Object singleton;

    PluginExtension(final Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public String getAttribute(final String name) {
        return attributes.get(name);
    }

    public String getImplementationClass() {
        return attributes.get("implementationClass");
    }

    public <T> T createExtension(final Class<T> extensionClass,
                                 final boolean createSingleton,
                                 final CompilerContext compilerContext) {
        final var implementationClass = getImplementationClass();
        if (implementationClass == null) {
            throw new IllegalArgumentException("No implementation class specified for extension " + extensionClass.getName());
        }
        try {
            final var clazz = Class.forName(implementationClass);
            if (!extensionClass.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException("Implementation class " + implementationClass + " is not assignable to " + extensionClass.getName());
            }

            final T instance;
            final var constructors = clazz.getDeclaredConstructors();

            if (constructors.length == 0) {
                throw new IllegalArgumentException("Implementation class " + implementationClass + " has no declared constructors");
            } else {
                final var constructor = constructors[0];
                if (constructor.getParameterCount() == 0) {
                    instance = (T) constructor.newInstance();
                } else {
                    instance = (T) constructor.newInstance(compilerContext);
                }
            }

            if (createSingleton) {
                this.singleton = instance;
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create extension " + extensionClass.getName() + " with implementation class " + implementationClass, e);
        }
    }

}

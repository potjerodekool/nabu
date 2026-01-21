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

    public boolean getBooleanAttribute(final String name,
                                       final boolean defaultValue) {
        final var value = getAttribute(name);

        if (value == null) {
            return defaultValue;
        } else {
            return Boolean.valueOf(value);
        }
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

    public boolean supportsJdkFeatureVersion(final int version) {
        final var min = getMinJdk();

        if (version < min) {
            return false;
        }

        final var maxJdk = getMaxJdk();
        return maxJdk == -1 || version < maxJdk;
    }

    private int getMinJdk() {
        return tryParseInt(attributes.get("minJdk"), 1);
    }

    private int getMaxJdk() {
        return tryParseInt(attributes.get("maxJdk"), -1);
    }

    private int tryParseInt(final String value,
                            final int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            return defaultValue;
        }
    }

}

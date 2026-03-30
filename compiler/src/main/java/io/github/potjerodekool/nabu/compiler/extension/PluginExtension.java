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

    public Object getSingleton() {
        return singleton;
    }

    public void setSingleton(final Object singleton) {
        this.singleton = singleton;
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

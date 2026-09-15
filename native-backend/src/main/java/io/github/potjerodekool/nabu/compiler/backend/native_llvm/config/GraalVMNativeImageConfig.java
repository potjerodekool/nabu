package io.github.potjerodekool.nabu.compiler.backend.native_llvm.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Model van de GraalVM-native-image-configuraties die picocli-codegen
 * genereert: {@code reflect-config.json}, {@code resource-config.json} en
 * {@code proxy-config.json} onder {@code META-INF/native-image/**}.
 */
public final class GraalVMNativeImageConfig {

    private final List<ReflectEntry> reflectEntries;
    private final ResourceConfig resources;
    private final List<ProxyEntry> proxyEntries;

    public GraalVMNativeImageConfig(final List<ReflectEntry> reflectEntries,
                                    final ResourceConfig resources,
                                    final List<ProxyEntry> proxyEntries) {
        this.reflectEntries = List.copyOf(reflectEntries);
        this.resources = resources;
        this.proxyEntries = List.copyOf(proxyEntries);
    }

    public List<ReflectEntry> reflectEntries() {
        return reflectEntries;
    }

    public ResourceConfig resources() {
        return resources;
    }

    public List<ProxyEntry> proxyEntries() {
        return proxyEntries;
    }

    public record ReflectEntry(String name,
                               boolean allDeclaredConstructors,
                               boolean allPublicConstructors,
                               boolean allDeclaredMethods,
                               boolean allPublicMethods,
                               List<FieldEntry> fields) {

        public List<String> fieldNames() {
            final var names = new ArrayList<String>();
            for (final var field : fields) {
                names.add(field.name());
            }
            return names;
        }
    }

    public record FieldEntry(String name) {
    }

    public record ProxyEntry(List<String> interfaceNames) {
    }

    public record ResourceConfig(List<String> bundles,
                                 List<ResourcePattern> resources) {
    }

    public record ResourcePattern(String pattern) {
    }
}

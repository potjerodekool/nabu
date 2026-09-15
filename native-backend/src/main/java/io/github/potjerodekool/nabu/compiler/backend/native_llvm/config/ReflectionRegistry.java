package io.github.potjerodekool.nabu.compiler.backend.native_llvm.config;

import java.util.LinkedHashMap;
import java.util.Map;/**
 * Registratie van reflect-config-entries per batch: interne namen (met
 * {@code /}-scheiding, zoals in de IR) naar
 * {@link GraalVMNativeImageConfig.ReflectEntry}-objecten.
 */
public final class ReflectionRegistry {

    private final Map<String, GraalVMNativeImageConfig.ReflectEntry> entriesByInternalName =
            new LinkedHashMap<>();

    public void register(final GraalVMNativeImageConfig config) {
        for (final var entry : config.reflectEntries()) {
            entriesByInternalName.put(internalName(entry.name()), entry);
        }
    }

    public GraalVMNativeImageConfig.ReflectEntry entryFor(final String internalName) {
        return entriesByInternalName.get(internalName(internalName));
    }

    public GraalVMNativeImageConfig.ReflectEntry entryForClassName(final String fqName) {
        return entriesByInternalName.get(internalName(fqName));
    }

    public boolean hasField(final String fqClassName, final String fieldName) {
        final var entry = entryForClassName(fqClassName);
        return entry != null && entry.fieldNames().contains(fieldName);
    }

    public int size() {
        return entriesByInternalName.size();
    }

    private static String internalName(final String name) {
        return name.replace('.', '/');
    }
}

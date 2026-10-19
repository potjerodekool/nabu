package io.github.potjerodekool.nabu.backend.nativeimage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Model van de GraalVM-native-image-configuraties die picocli-codegen
 * genereert: {@code reflect-config.json}, {@code resource-config.json} en
 * {@code proxy-config.json} onder {@code META-INF/native-image/**}, en het
 * nieuwere {@code reachability-metadata.json}.
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
                               List<FieldEntry> fields,
                               List<MethodEntry> methods,
                               boolean allDeclaredFields,
                               boolean allPublicFields) {

        public ReflectEntry(final String name,
                            final boolean allDeclaredConstructors,
                            final boolean allPublicConstructors,
                            final boolean allDeclaredMethods,
                            final boolean allPublicMethods,
                            final List<FieldEntry> fields,
                            final List<MethodEntry> methods) {
            this(name, allDeclaredConstructors, allPublicConstructors, allDeclaredMethods, allPublicMethods,
                    fields, methods, false, false);
        }

        /**
         * Whether a field declared by this class is registered: by name, or
         * through {@code allDeclaredFields} / {@code allPublicFields}.
         */
        public boolean includesField(final String fieldName, final boolean isPublic) {
            return allDeclaredFields || (allPublicFields && isPublic) || fieldNames().contains(fieldName);
        }

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

    /** Een expliciet genoemde methode; {@code <init>} voor een constructor. */
    public record MethodEntry(String name, List<String> parameterTypes) {
    }

    public record ProxyEntry(List<String> interfaceNames) {
    }

    /**
     * Resources to include: {@code resources} are the include patterns,
     * {@code excludes} remove matches again (as in {@code native-image}).
     */
    public record ResourceConfig(List<String> bundles,
                                 List<ResourcePattern> resources,
                                 List<ResourcePattern> excludes) {

        public ResourceConfig(final List<String> bundles, final List<ResourcePattern> resources) {
            this(bundles, resources, List.of());
        }
    }

    /** A regular expression over the resource path (no leading slash). */
    public record ResourcePattern(String pattern) {

        /**
         * A GraalVM resource glob: {@code *} matches within one path level,
         * {@code **} any number of levels.
         */
        public static ResourcePattern fromGlob(final String glob) {
            final var regex = new StringBuilder();
            for (int i = 0; i < glob.length(); i++) {
                final char c = glob.charAt(i);
                if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else if (c == '*') {
                    regex.append("[^/]*");
                } else if (c == '?') {
                    regex.append("[^/]");
                } else if ("\\.[]{}()<>+-=^$|!".indexOf(c) >= 0) {
                    regex.append('\\').append(c);
                } else {
                    regex.append(c);
                }
            }
            return new ResourcePattern(regex.toString());
        }
    }
}

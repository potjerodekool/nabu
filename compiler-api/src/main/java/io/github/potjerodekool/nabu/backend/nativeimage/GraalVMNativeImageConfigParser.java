package io.github.potjerodekool.nabu.backend.nativeimage;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed {@code reflect-config.json}, {@code resource-config.json} en
 * {@code proxy-config.json} (GraalVM-formaten, zoals gegenereerd door
 * picocli-codegen) naar {@link GraalVMNativeImageConfig}­objecten.
 */
public final class GraalVMNativeImageConfigParser {

    private GraalVMNativeImageConfigParser() {
    }

    public static GraalVMNativeImageConfig parseReflect(final String json) {
        final Object root = TinyJson.parse(json);
        final var entries = new ArrayList<GraalVMNativeImageConfig.ReflectEntry>();

        if (root instanceof List<?> list) {
            for (final var item : list) {
                if (item instanceof java.util.Map<?, ?> map) {
                    entries.add(parseReflectEntry(map));
                }
            }
        }

        return new GraalVMNativeImageConfig(entries, null, List.of());
    }

    /**
     * Het nieuwere enkelvoudige formaat ({@code reachability-metadata.json}):
     * een object met o.a. een {@code reflection}-lijst waarvan elke entry de
     * klasse in {@code type} noemt. Entries met een niet-string {@code type}
     * (proxies, lambda's) worden overgeslagen.
     */
    public static GraalVMNativeImageConfig parseReachabilityMetadata(final String json) {
        final Object root = TinyJson.parse(json);
        final var entries = new ArrayList<GraalVMNativeImageConfig.ReflectEntry>();

        if (root instanceof java.util.Map<?, ?> map
                && map.get("reflection") instanceof List<?> list) {
            for (final var item : list) {
                if (item instanceof java.util.Map<?, ?> entry
                        && entry.get("type") instanceof String type) {
                    entries.add(parseReflectEntry(type, entry));
                }
            }
        }

        return new GraalVMNativeImageConfig(entries, null, List.of());
    }

    public static GraalVMNativeImageConfig parseResource(final String json) {
        final Object root = TinyJson.parse(json);
        final var bundles = new ArrayList<String>();
        final var patterns = new ArrayList<GraalVMNativeImageConfig.ResourcePattern>();

        if (root instanceof java.util.Map<?, ?> map) {
            final var bundlesValue = map.get("bundles");
            if (bundlesValue instanceof List<?> bundleList) {
                for (final var item : bundleList) {
                    if (item instanceof java.util.Map<?, ?> bundle
                            && bundle.get("name") != null) {
                        bundles.add(String.valueOf(bundle.get("name")));
                    }
                }
            }
            final var resourcesValue = map.get("resources");
            if (resourcesValue instanceof List<?> resourceList) {
                for (final var item : resourceList) {
                    if (item instanceof java.util.Map<?, ?> resource
                            && resource.get("pattern") != null) {
                        patterns.add(new GraalVMNativeImageConfig.ResourcePattern(
                                String.valueOf(resource.get("pattern"))));
                    }
                }
            }
        }

        return new GraalVMNativeImageConfig(
                List.of(),
                new GraalVMNativeImageConfig.ResourceConfig(bundles, patterns),
                List.of());
    }

    public static GraalVMNativeImageConfig parseProxy(final String json) {
        final Object root = TinyJson.parse(json);
        final var entries = new ArrayList<GraalVMNativeImageConfig.ProxyEntry>();

        if (root instanceof List<?> list) {
            for (final var item : list) {
                if (item instanceof java.util.Map<?, ?> map) {
                    final var interfaceList = (List<?>) map.get("interfaces");
                    if (interfaceList != null) {
                        final var interfaces = interfaceList.stream()
                                .map(String::valueOf)
                                .toList();
                        entries.add(new GraalVMNativeImageConfig.ProxyEntry(interfaces));
                    }
                }
            }
        }

        return new GraalVMNativeImageConfig(List.of(), null, entries);
    }

    private static GraalVMNativeImageConfig.ReflectEntry parseReflectEntry(
            final java.util.Map<?, ?> map) {
        return parseReflectEntry(String.valueOf(map.get("name")), map);
    }

    private static GraalVMNativeImageConfig.ReflectEntry parseReflectEntry(
            final String name,
            final java.util.Map<?, ?> map) {
        return new GraalVMNativeImageConfig.ReflectEntry(
                name,
                truthy(map.get("allDeclaredConstructors")),
                truthy(map.get("allPublicConstructors")),
                truthy(map.get("allDeclaredMethods")),
                truthy(map.get("allPublicMethods")),
                parseFields(map.get("fields")),
                parseMethods(map.get("methods")));
    }

    private static List<GraalVMNativeImageConfig.MethodEntry> parseMethods(final Object value) {
        final var methods = new ArrayList<GraalVMNativeImageConfig.MethodEntry>();
        if (value instanceof List<?> raw) {
            for (final var item : raw) {
                if (item instanceof java.util.Map<?, ?> methodMap
                        && methodMap.get("name") != null) {
                    final var parameterTypes = methodMap.get("parameterTypes") instanceof List<?> types
                            ? types.stream().map(String::valueOf).toList()
                            : List.<String>of();
                    methods.add(new GraalVMNativeImageConfig.MethodEntry(
                            String.valueOf(methodMap.get("name")), parameterTypes));
                }
            }
        }
        return methods;
    }

    private static List<GraalVMNativeImageConfig.FieldEntry> parseFields(final Object value) {
        final var fields = new ArrayList<GraalVMNativeImageConfig.FieldEntry>();
        if (value instanceof List<?> raw) {
            for (final var item : raw) {
                if (item instanceof java.util.Map<?, ?> fieldMap
                        && fieldMap.get("name") != null) {
                    fields.add(new GraalVMNativeImageConfig.FieldEntry(
                            String.valueOf(fieldMap.get("name"))));
                }
            }
        }
        return fields;
    }

    private static boolean truthy(final Object value) {
        return Boolean.TRUE.equals(value);
    }
}

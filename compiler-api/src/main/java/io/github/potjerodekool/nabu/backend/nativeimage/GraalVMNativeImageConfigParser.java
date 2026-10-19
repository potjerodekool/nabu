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
     * (proxies, lambda's) worden overgeslagen. Daarnaast {@code resources}
     * (entries met een {@code glob}) en {@code bundles}.
     */
    public static GraalVMNativeImageConfig parseReachabilityMetadata(final String json) {
        final Object root = TinyJson.parse(json);
        final var entries = new ArrayList<GraalVMNativeImageConfig.ReflectEntry>();
        final var bundles = new ArrayList<String>();
        final var includes = new ArrayList<GraalVMNativeImageConfig.ResourcePattern>();
        final var proxies = new ArrayList<GraalVMNativeImageConfig.ProxyEntry>();

        if (root instanceof java.util.Map<?, ?> map) {
            if (map.get("reflection") instanceof List<?> list) {
                for (final var item : list) {
                    if (item instanceof java.util.Map<?, ?> entry) {
                        if (entry.get("type") instanceof String type) {
                            entries.add(parseReflectEntry(type, entry));
                        } else if (entry.get("type") instanceof java.util.Map<?, ?> type
                                && type.get("proxy") instanceof List<?> interfaces) {
                            // A proxy class: "type": { "proxy": [ "a.I", ... ] }.
                            proxies.add(new GraalVMNativeImageConfig.ProxyEntry(
                                    interfaces.stream().map(String::valueOf).toList()));
                        }
                    }
                }
            }
            parsePatterns(map.get("resources"), includes);
            parseBundles(map.get("bundles"), bundles);
        }

        return new GraalVMNativeImageConfig(entries,
                new GraalVMNativeImageConfig.ResourceConfig(bundles, includes, List.of()), proxies);
    }

    /**
     * {@code resource-config.json} in each of its forms:
     * <ul>
     *   <li>{@code "resources": {"includes": [...], "excludes": [...]}};</li>
     *   <li>the older {@code "resources": [{"pattern": ...}]};</li>
     *   <li>{@code "globs": [{"glob": ...}]};</li>
     * </ul>
     * an entry has a {@code pattern} (regular expression) or a {@code glob};
     * {@code bundles} lists resource bundles by {@code name}.
     */
    public static GraalVMNativeImageConfig parseResource(final String json) {
        final Object root = TinyJson.parse(json);
        final var bundles = new ArrayList<String>();
        final var includes = new ArrayList<GraalVMNativeImageConfig.ResourcePattern>();
        final var excludes = new ArrayList<GraalVMNativeImageConfig.ResourcePattern>();

        if (root instanceof java.util.Map<?, ?> map) {
            parseBundles(map.get("bundles"), bundles);
            final var resourcesValue = map.get("resources");
            if (resourcesValue instanceof java.util.Map<?, ?> resources) {
                parsePatterns(resources.get("includes"), includes);
                parsePatterns(resources.get("excludes"), excludes);
            } else {
                parsePatterns(resourcesValue, includes);
            }
            parsePatterns(map.get("globs"), includes);
        }

        return new GraalVMNativeImageConfig(
                List.of(),
                new GraalVMNativeImageConfig.ResourceConfig(bundles, includes, excludes),
                List.of());
    }

    private static void parseBundles(final Object value, final List<String> bundles) {
        if (value instanceof List<?> bundleList) {
            for (final var item : bundleList) {
                if (item instanceof java.util.Map<?, ?> bundle && bundle.get("name") != null) {
                    bundles.add(String.valueOf(bundle.get("name")));
                }
            }
        }
    }

    private static void parsePatterns(final Object value,
                                      final List<GraalVMNativeImageConfig.ResourcePattern> patterns) {
        if (value instanceof List<?> list) {
            for (final var item : list) {
                if (item instanceof java.util.Map<?, ?> resource) {
                    if (resource.get("pattern") != null) {
                        patterns.add(new GraalVMNativeImageConfig.ResourcePattern(
                                String.valueOf(resource.get("pattern"))));
                    } else if (resource.get("glob") != null) {
                        patterns.add(GraalVMNativeImageConfig.ResourcePattern.fromGlob(
                                String.valueOf(resource.get("glob"))));
                    }
                }
            }
        }
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
                parseMethods(map.get("methods")),
                truthy(map.get("allDeclaredFields")),
                truthy(map.get("allPublicFields")));
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

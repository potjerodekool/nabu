package io.github.potjerodekool.nabu.backend.nativeimage;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Vindt alle GraalVM-native-image-configuraties onder
 * {@code META-INF/native-image} in de opgegeven classpath-roots en leest ze
 * tot {@link GraalVMNativeImageConfig}-objecten. Een root is een map of een
 * jar, net als bij {@code native-image}: een library kan zo zijn eigen
 * configuratie meeleveren.
 *
 * <p>Gelezen worden {@code reflect-config.json}, {@code resource-config.json},
 * {@code proxy-config.json}, het nieuwere {@code reachability-metadata.json}
 * en {@code native-image.properties}. Van de {@code Args} in dat laatste
 * tellen de opties die configuratie aanwijzen of resources selecteren (zie
 * {@link #readProperties}); de rest (initialisatie, GC, ...) heeft voor de
 * LLVM-backend geen betekenis.
 */
public final class NativeImageConfigScanner {

    private static final String RESOURCES_SUFFIX = "ConfigurationResources";
    private static final String FILES_SUFFIX = "ConfigurationFiles";

    private NativeImageConfigScanner() {
    }

    public static List<GraalVMNativeImageConfig> scan(final List<Path> classPathRoots) {
        final var configs = new ArrayList<GraalVMNativeImageConfig>();
        for (final var root : classPathRoots) {
            if (root == null) {
                continue;
            }
            if (Files.isDirectory(root)) {
                scanRoot(root, configs);
            } else if (Files.isRegularFile(root) && root.getFileName().toString().endsWith(".jar")) {
                scanJar(root, configs);
            }
        }
        return configs;
    }

    private static void scanJar(final Path jar, final List<GraalVMNativeImageConfig> configs) {
        try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), Map.of())) {
            scanRoot(zip.getPath("/"), configs);
        } catch (IOException | RuntimeException e) {
            // Onleesbare jar: overslaan
        }
    }

    private static void scanRoot(final Path root, final List<GraalVMNativeImageConfig> configs) {
        final var nativeImageRoot = root.resolve("META-INF").resolve("native-image");
        if (!Files.isDirectory(nativeImageRoot)) {
            return;
        }
        // Each file once: native-image.properties often names the files that
        // the scan finds anyway (${.}/reflect-config.json).
        final Set<Path> read = new HashSet<>();
        final List<Path> files;
        try (Stream<Path> paths = Files.walk(nativeImageRoot)) {
            files = paths.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            // Onleesbare root: overslaan
            return;
        }
        for (final Path file : files) {
            if ("native-image.properties".equals(file.getFileName().toString())) {
                readProperties(root, file, configs, read);
            } else {
                readConfig(file, kindOf(file.getFileName().toString()), configs, read);
            }
        }
    }

    private enum Kind { REFLECT, RESOURCE, PROXY, REACHABILITY }

    private static Kind kindOf(final String fileName) {
        return switch (fileName) {
            case "reflect-config.json" -> Kind.REFLECT;
            case "resource-config.json" -> Kind.RESOURCE;
            case "proxy-config.json" -> Kind.PROXY;
            case "reachability-metadata.json" -> Kind.REACHABILITY;
            default -> null;
        };
    }

    private static void readConfig(final Path configPath,
                                   final Kind kind,
                                   final List<GraalVMNativeImageConfig> configs,
                                   final Set<Path> read) {
        if (kind == null || !read.add(configPath.toAbsolutePath().normalize())) {
            return;
        }
        try {
            final String json = Files.readString(configPath);
            configs.add(switch (kind) {
                case REFLECT -> GraalVMNativeImageConfigParser.parseReflect(json);
                case RESOURCE -> GraalVMNativeImageConfigParser.parseResource(json);
                case PROXY -> GraalVMNativeImageConfigParser.parseProxy(json);
                case REACHABILITY -> GraalVMNativeImageConfigParser.parseReachabilityMetadata(json);
            });
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[nabu] warning: skipping unreadable native-image config "
                    + configPath + ": " + e.getMessage());
        }
    }

    /**
     * The {@code Args} of a {@code native-image.properties}:
     * <ul>
     *   <li>{@code -H:ReflectionConfigurationResources},
     *       {@code -H:ResourceConfigurationResources},
     *       {@code -H:DynamicProxyConfigurationResources} (class-path
     *       resources of the same root, {@code ${.}} being the directory of
     *       the properties file) and their {@code ...ConfigurationFiles}
     *       variants (file-system paths);</li>
     *   <li>{@code -H:ConfigurationFileDirectories}: directories with the
     *       standard file names;</li>
     *   <li>{@code -H:IncludeResources} / {@code -H:ExcludeResources}
     *       (regular expressions) and {@code -H:IncludeResourceBundles}.</li>
     * </ul>
     * Values are comma-separated lists.
     */
    private static void readProperties(final Path root,
                                       final Path propertiesFile,
                                       final List<GraalVMNativeImageConfig> configs,
                                       final Set<Path> read) {
        final Properties properties = new Properties();
        try {
            properties.load(new StringReader(Files.readString(propertiesFile)));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[nabu] warning: skipping unreadable " + propertiesFile + ": " + e.getMessage());
            return;
        }
        final String args = properties.getProperty("Args");
        if (args == null) {
            return;
        }
        final String directory = root.relativize(propertiesFile.getParent()).toString().replace('\\', '/');
        final List<String> bundles = new ArrayList<>();
        final List<GraalVMNativeImageConfig.ResourcePattern> includes = new ArrayList<>();
        final List<GraalVMNativeImageConfig.ResourcePattern> excludes = new ArrayList<>();
        for (final String arg : args.trim().split("\\s+")) {
            final int equals = arg.indexOf('=');
            if (!arg.startsWith("-H:") || equals < 0) {
                continue;
            }
            final String option = arg.substring("-H:".length(), equals);
            final String value = arg.substring(equals + 1);
            switch (option) {
                case "IncludeResources" -> includes.add(new GraalVMNativeImageConfig.ResourcePattern(value));
                case "ExcludeResources" -> excludes.add(new GraalVMNativeImageConfig.ResourcePattern(value));
                case "IncludeResourceBundles" -> bundles.addAll(List.of(value.split(",")));
                case "ConfigurationFileDirectories" -> {
                    for (final String dir : value.split(",")) {
                        final Path path = Path.of(dir);
                        for (final String name : List.of("reflect-config.json", "resource-config.json",
                                "proxy-config.json", "reachability-metadata.json")) {
                            if (Files.isRegularFile(path.resolve(name))) {
                                readConfig(path.resolve(name), kindOf(name), configs, read);
                            }
                        }
                    }
                }
                default -> {
                    final Kind kind = optionKind(option);
                    if (kind == null) {
                        continue;
                    }
                    for (final String item : value.split(",")) {
                        final String resolved = item.replace("${.}", directory);
                        final Path path = option.endsWith(RESOURCES_SUFFIX)
                                ? root.resolve(resolved.startsWith("/") ? resolved.substring(1) : resolved)
                                : Path.of(resolved);
                        if (Files.isRegularFile(path)) {
                            readConfig(path, kind, configs, read);
                        } else {
                            System.err.println("[nabu] warning: " + propertiesFile + ": " + arg.substring(0, equals)
                                    + " names " + item + ", which does not exist");
                        }
                    }
                }
            }
        }
        if (!includes.isEmpty() || !excludes.isEmpty() || !bundles.isEmpty()) {
            configs.add(new GraalVMNativeImageConfig(List.of(),
                    new GraalVMNativeImageConfig.ResourceConfig(bundles, includes, excludes), List.of()));
        }
    }

    private static Kind optionKind(final String option) {
        final String base;
        if (option.endsWith(RESOURCES_SUFFIX)) {
            base = option.substring(0, option.length() - RESOURCES_SUFFIX.length());
        } else if (option.endsWith(FILES_SUFFIX)) {
            base = option.substring(0, option.length() - FILES_SUFFIX.length());
        } else {
            return null;
        }
        return switch (base) {
            case "Reflection" -> Kind.REFLECT;
            case "Resource" -> Kind.RESOURCE;
            case "DynamicProxy" -> Kind.PROXY;
            default -> null;
        };
    }
}

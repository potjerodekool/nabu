package io.github.potjerodekool.nabu.backend.nativeimage;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Vindt alle GraalVM-native-image-configuraties onder
 * {@code META-INF/native-image} in de opgegeven classpath-roots en leest ze
 * tot {@link GraalVMNativeImageConfig}-objecten. Een root is een map of een
 * jar, net als bij {@code native-image}: een library kan zo zijn eigen
 * configuratie meeleveren.
 *
 * <p>Gelezen worden {@code reflect-config.json}, {@code resource-config.json},
 * {@code proxy-config.json} en het nieuwere {@code reachability-metadata.json}
 * (alleen de {@code reflection}-sectie).
 */
public final class NativeImageConfigScanner {

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
        try (Stream<Path> paths = Files.walk(nativeImageRoot)) {
            paths.filter(Files::isRegularFile)
                    .forEach(configPath -> readConfig(configPath, configs));
        } catch (IOException e) {
            // Onleesbare root: overslaan
        }
    }

    private static void readConfig(final Path configPath,
                                   final List<GraalVMNativeImageConfig> configs) {
        final var fileName = configPath.getFileName().toString();
        try {
            switch (fileName) {
                case "reflect-config.json" ->
                        configs.add(GraalVMNativeImageConfigParser.parseReflect(Files.readString(configPath)));
                case "resource-config.json" ->
                        configs.add(GraalVMNativeImageConfigParser.parseResource(Files.readString(configPath)));
                case "proxy-config.json" ->
                        configs.add(GraalVMNativeImageConfigParser.parseProxy(Files.readString(configPath)));
                case "reachability-metadata.json" ->
                        configs.add(GraalVMNativeImageConfigParser.parseReachabilityMetadata(
                                Files.readString(configPath)));
                default -> {
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[nabu] warning: skipping unreadable native-image config "
                    + configPath + ": " + e.getMessage());
        }
    }
}

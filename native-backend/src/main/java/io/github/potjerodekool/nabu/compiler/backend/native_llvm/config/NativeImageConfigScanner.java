package io.github.potjerodekool.nabu.compiler.backend.native_llvm.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Vindt alle GraalVM-native-image-configuraties (JSON-bestanden met de naam
 * "...-config.json" onder {@code META-INF/native-image}) in de opgegeven
 * classpath-roots en leest ze tot {@link GraalVMNativeImageConfig}-objecten.
 */
public final class NativeImageConfigScanner {

    private static final String NATIVE_IMAGE_DIR = "META-INF/native-image";

    private NativeImageConfigScanner() {
    }

    public static List<GraalVMNativeImageConfig> scan(final List<Path> classPathRoots) {
        final var configs = new ArrayList<GraalVMNativeImageConfig>();
        for (final var root : classPathRoots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            final var nativeImageRoot = root.resolve("META-INF").resolve("native-image");
            if (Files.isDirectory(nativeImageRoot)) {
                configs.addAll(collectConfigs(nativeImageRoot));
            }
        }
        return configs;
    }

    private static List<GraalVMNativeImageConfig> collectConfigs(
            final Path nativeImageRoot) {
        final var configs = new ArrayList<GraalVMNativeImageConfig>();
        try (Stream<Path> paths = Files.walk(nativeImageRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("-config.json"))
                    .forEach(configPath -> readConfig(configPath, configs));
        } catch (IOException e) {
            // Onleesbare root: overslaan
        }
        return configs;
    }

    private static void readConfig(final Path configPath,
                                   final List<GraalVMNativeImageConfig> configs) {
        try {
            final var json = Files.readString(configPath);
            final var fileName = configPath.getFileName().toString();
            if (fileName.equals("reflect-config.json")) {
                configs.add(GraalVMNativeImageConfigParser.parseReflect(json));
            } else if (fileName.equals("resource-config.json")) {
                configs.add(GraalVMNativeImageConfigParser.parseResource(json));
            } else if (fileName.equals("proxy-config.json")) {
                configs.add(GraalVMNativeImageConfigParser.parseProxy(json));
            }
        } catch (IOException e) {
            // Overslaan
        }
    }
}

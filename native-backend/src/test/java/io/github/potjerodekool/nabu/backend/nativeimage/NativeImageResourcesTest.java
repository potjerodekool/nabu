package io.github.potjerodekool.nabu.backend.nativeimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resource configuration formats (includes/excludes, the older list,
 * globs, {@code reachability-metadata.json}) and which class-path files they
 * select.
 */
class NativeImageResourcesTest {

    @TempDir
    Path tempDir;

    @Test
    void includesAndExcludes() {
        final var config = GraalVMNativeImageConfigParser.parseResource("""
                {
                  "resources": {
                    "includes": [ { "pattern": "a/.*" }, { "glob": "b/*.txt" } ],
                    "excludes": [ { "pattern": "a/secret.*" } ]
                  },
                  "globs": [ { "glob": "c/**" } ],
                  "bundles": [ { "name": "m.Messages" } ]
                }
                """).resources();

        assertEquals(List.of("a/.*", "b/[^/]*\\.txt", "c/.*"),
                config.resources().stream().map(GraalVMNativeImageConfig.ResourcePattern::pattern).toList());
        assertEquals(List.of("a/secret.*"),
                config.excludes().stream().map(GraalVMNativeImageConfig.ResourcePattern::pattern).toList());
        assertEquals(List.of("m.Messages"), config.bundles());
    }

    @Test
    void reachabilityMetadataResources() {
        final var config = GraalVMNativeImageConfigParser.parseReachabilityMetadata("""
                {
                  "reflection": [ { "type": "a.One" } ],
                  "resources": [ { "glob": "**/x.json" }, { "module": "m", "glob": "y.txt" } ],
                  "bundles": [ { "name": "m.Messages" } ]
                }
                """);

        assertEquals(1, config.reflectEntries().size());
        assertEquals(2, config.resources().resources().size());
        assertEquals(List.of("m.Messages"), config.resources().bundles());
    }

    @Test
    void globs() {
        assertTrue(matches("**/x.json", "x.json"));
        assertTrue(matches("**/x.json", "a/b/x.json"));
        assertTrue(matches("a/*.txt", "a/b.txt"));
        assertFalse(matches("a/*.txt", "a/b/c.txt"));
        assertTrue(matches("a/**", "a/b/c.txt"));
        assertFalse(matches("a/*.txt", "a/bxtxt"));
    }

    private static boolean matches(final String glob, final String path) {
        return Pattern.matches(GraalVMNativeImageConfig.ResourcePattern.fromGlob(glob).pattern(), path);
    }

    @Test
    void selectsMatchingFilesFirstRootWins() throws Exception {
        final Path first = tempDir.resolve("first");
        final Path second = tempDir.resolve("second");
        write(first.resolve("a/one.txt"), "first");
        write(second.resolve("a/one.txt"), "second");
        write(second.resolve("a/secret.txt"), "no");
        write(second.resolve("other.txt"), "no");
        write(second.resolve("m/Messages_nl.properties"), "k=v");
        write(second.resolve("m/Messages_de.class"), "");

        final var config = GraalVMNativeImageConfigParser.parseResource("""
                {
                  "resources": {
                    "includes": [ { "pattern": "a/.*" } ],
                    "excludes": [ { "pattern": "a/secret.*" } ]
                  },
                  "bundles": [ { "name": "m.Messages" } ]
                }
                """);
        final var resources = NativeImageResources.collect(List.of(config), List.of(first, second));

        assertEquals(List.of("a/one.txt", "m/Messages_nl.properties"), List.copyOf(resources.resources().keySet()));
        assertEquals("first", new String(resources.resources().get("a/one.txt")));
        assertEquals(List.of("m.Messages_de"), resources.bundleClasses());
        assertTrue(resources.isBundleClass("m.Messages"));
        assertTrue(resources.isBundleClass("m.Messages_fr_FR"));
        assertFalse(resources.isBundleClass("m.MessagesX"));
    }

    /**
     * {@code native-image.properties}: configuration named by its
     * {@code Args} ({@code ${.}} is the directory of the file) is read, a
     * standard-named file only once, and resource options select resources.
     */
    @Test
    void nativeImagePropertiesArgs() throws Exception {
        final Path root = tempDir.resolve("root");
        final Path dir = root.resolve("META-INF/native-image/org.example/lib");
        write(dir.resolve("native-image.properties"),
                "Args = --initialize-at-build-time=org.example \\\n"
                        + "       -H:ReflectionConfigurationResources=${.}/reflection.json,${.}/reflect-config.json \\\n"
                        + "       -H:IncludeResources=data/.* -H:ExcludeResources=data/skip.txt \\\n"
                        + "       -H:IncludeResourceBundles=m.Msgs\n");
        write(dir.resolve("reflection.json"), "[ { \"name\": \"a.One\", \"allPublicFields\": true } ]");
        write(dir.resolve("reflect-config.json"), "[ { \"name\": \"a.Two\" } ]");
        write(root.resolve("data/info.txt"), "x");
        write(root.resolve("data/skip.txt"), "x");
        write(root.resolve("m/Msgs.properties"), "k=v");

        final var configs = NativeImageConfigScanner.scan(List.of(root));
        final var reflected = configs.stream().flatMap(c -> c.reflectEntries().stream())
                .map(GraalVMNativeImageConfig.ReflectEntry::name).sorted().toList();
        assertEquals(List.of("a.One", "a.Two"), reflected);
        assertTrue(configs.stream().flatMap(c -> c.reflectEntries().stream())
                .anyMatch(GraalVMNativeImageConfig.ReflectEntry::allPublicFields));

        final var resources = NativeImageResources.collect(configs, List.of(root));
        assertEquals(List.of("data/info.txt", "m/Msgs.properties"), List.copyOf(resources.resources().keySet()));
    }

    private static void write(final Path file, final String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}

package io.github.potjerodekool.nabu.compiler.backend.native_llvm.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fase 1 (config-consumer): parsers en scanner, met echte
 * picocli-codegen-output als fixture.
 */
class NativeImageConfigTest {

    /** De echte output van picocli-codegen voor het greet-demo-commando. */
    private static final String GREET_REFLECT_CONFIG = """
            [
              {
                "name" : "greet.GreetCommand",
                "allDeclaredConstructors" : true,
                "allPublicConstructors" : true,
                "allDeclaredMethods" : true,
                "allPublicMethods" : true,
                "fields" : [
                  { "name" : "count" },
                  { "name" : "name" }
                ]
              }
            ]
            """;

    @Test
    void parseReflectSingleEntry() {
        final var config = GraalVMNativeImageConfigParser.parseReflect(GREET_REFLECT_CONFIG);

        assertEquals(1, config.reflectEntries().size());
        final var entry = config.reflectEntries().getFirst();
        assertEquals("greet.GreetCommand", entry.name());
        assertTrue(entry.allDeclaredConstructors());
        assertTrue(entry.allPublicMethods());
        assertTrue(entry.allDeclaredMethods());
        assertEquals(List.of("count", "name"), entry.fieldNames());
    }

    @Test
    void parseReflectMultipleEntries() {
        final var config = GraalVMNativeImageConfigParser.parseReflect("""
                [
                  {
                    "name" : "a.One",
                    "allDeclaredMethods" : true,
                    "fields" : [ { "name": "id" } ]
                  },
                  {
                    "name" : "a.Two"
                  }
                ]
                """);

        assertEquals(2, config.reflectEntries().size());
        assertEquals("a.One", config.reflectEntries().get(0).name());
        assertEquals(List.of("id"), config.reflectEntries().get(0).fieldNames());
        assertEquals("a.Two", config.reflectEntries().get(1).name());
        assertFalse(config.reflectEntries().get(1).allDeclaredMethods());
    }

    @Test
    void parseResourceEmpty() {
        final var config = GraalVMNativeImageConfigParser.parseResource("""
                {
                  "bundles" : [
                  ],
                  "resources" : [
                  ]
                }
                """);

        assertNotNull(config.resources());
        assertTrue(config.resources().bundles().isEmpty());
        assertTrue(config.resources().resources().isEmpty());
    }

    @Test
    void parseResourceWithEntries() {
        final var config = GraalVMNativeImageConfigParser.parseResource("""
                {
                  "bundles" : [ { "name": "n.ActionBundles" } ],
                  "resources" : [ { "pattern": "META-INF/usage.properties" } ]
                }
                """);

        assertEquals(1, config.resources().bundles().size());
        assertEquals(1, config.resources().resources().size());
        assertEquals("META-INF/usage.properties",
                config.resources().resources().getFirst().pattern());
    }

    @Test
    void parseProxyWithInterfaces() {
        final var config = GraalVMNativeImageConfigParser.parseProxy("""
                [
                  {
                    "interfaces" : [ "java.lang.Runnable", "java.util.function.Supplier" ]
                  }
                ]
                """);

        assertEquals(1, config.proxyEntries().size());
        assertEquals(List.of("java.lang.Runnable", "java.util.function.Supplier"),
                config.proxyEntries().getFirst().interfaceNames());
    }

    @Test
    void scanFindsConfigsInClassPathRoot(@TempDir final Path tempDir) throws IOException {
        final var generated = tempDir.resolve("META-INF")
                .resolve("native-image")
                .resolve("picocli-generated");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("reflect-config.json"), GREET_REFLECT_CONFIG);
        Files.writeString(generated.resolve("resource-config.json"),
                "{ \"bundles\": [], \"resources\": [] }");
        // JSON-bestand buiten native-image wordt genegeerd
        Files.writeString(tempDir.resolve("other.json"), "[]");

        final var configs = NativeImageConfigScanner.scan(List.of(tempDir));

        assertEquals(2, configs.size());
        final var reflect = configs.stream()
                .filter(c -> !c.reflectEntries().isEmpty())
                .findFirst()
                .orElseThrow();
        assertEquals("greet.GreetCommand", reflect.reflectEntries().getFirst().name());
    }

    @Test
    void scansMultipleRootPaths(@TempDir final Path firstDir,
                                @TempDir final Path secondDir) throws IOException {
        final var firstRoot = firstDir.resolve("META-INF")
                .resolve("native-image")
                .resolve("gen");
        Files.createDirectories(firstRoot);
        Files.writeString(firstRoot.resolve("reflect-config.json"), "[ { \"name\": \"rs.A\" } ]");

        final var secondRoot = secondDir.resolve("META-INF")
                .resolve("native-image")
                .resolve("pregen");
        Files.createDirectories(secondRoot);
        Files.writeString(secondRoot.resolve("reflect-config.json"), "[ { \"name\": \"rs.B\" } ]");

        final var configs = NativeImageConfigScanner.scan(List.of(firstDir, secondDir));
        assertEquals(2, configs.size());
    }

    @Test
    void scanIgnoresMissingRoots(@TempDir final Path tempDir) {
        final var absent = tempDir.resolve("bestaat-niet");
        final var configs = NativeImageConfigScanner.scan(List.of(absent));
        assertTrue(configs.isEmpty());
    }
}

package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.potjerodekool.nabu.compiler.extension.PluginClassLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * Het plugin-contract van de *gebouwde* plugin-jar: een {@link PluginClassLoader}
 * moet de backendklasse plus het {@code plugin.xml}-resourc kunnen laden uit de
 * shaded jar, en de jar moet de LLVM-native-dependencies meeleveren.
 *
 * <p>Deze checks staan bewust in een {@code *IT} en niet in een surefire-test:
 * de jar ontstaat pas in de {@code package}-fase, terwijl surefire in de
 * {@code test}-fase draait. Als onderdeel van een surefire-test sloeg de test zich
 * daarom stilletjes over in elke schone build.
 */
class PluginJarIT {

    /**
     * De shaded plugin-jar uit {@code target/}. Let op: {@code target/} bevat
     * ook de sources-, javadoc- en {@code original-}-jar; alleen de jar die de
     * backend én plugin.xml écht bevat telt mee.
     */
    private static Path builtPluginJar() throws Exception {
        final String backendClass = "io/github/potjerodekool/nabu/compiler/backend"
                + "/native_llvm/NativeLLVMBackend.class";

        try (var jars = Files.list(Path.of("target"))) {
            for (final var jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                try (var zip = new ZipFile(jar.toFile())) {
                    if (zip.getEntry(backendClass) != null && zip.getEntry("plugin.xml") != null) {
                        return jar;
                    }
                } catch (final IOException ignored) {
                    // Geen geldige zip of geen leesrechten: volgende kandidaat.
                }
            }
        }

        return null;
    }

    @Test
    void pluginClassLoaderLoadsTheBackendFromTheBuiltJar() throws Exception {
        final var jar = builtPluginJar();
        // Geen assumption-skip meer: op dit punt in de lifecycle (integration-test,
        // ná package) moet de jar er staan. Anders faalt de build met een duidelijke
        // boodschap in plaats van stilletjes niets te testen.
        assertNotNull(jar, "geen plugin-jar met backend + plugin.xml in target/ "
                + "(draai `mvn verify`, niet `mvn failsafe:integration-test` los)");

        // De plugin-jar bevat alleen de backend; de compiler-klassen (Backend,
        // CompileOptions, ...) worden door de host-loader geleverd — precies
        // zoals de daemon het doet. Een 'null'-parent kan hier dus niet mee.
        final var loader = new PluginClassLoader(
                jar.toUri().toURL(),
                NativeLLVMBackend.class.getClassLoader());

        try {
            final var backend = loader.loadClass(
                    "io.github.potjerodekool.nabu.compiler.backend.native_llvm.NativeLLVMBackend");
            assertEquals("NativeLLVMBackend", backend.getSimpleName());
        } catch (final ClassNotFoundException | NoClassDefFoundError e) {
            fail("De plugin-jar is niet zelfstandig bruikbaar: " + e);
        } finally {
            loader.close();
        }

        // De shaded jar moet de registratie én de LLVM-native-dependencies
        // meeleveren, anders werkt de plugin-uitrol in een schone omgeving niet.
        try (var zip = new ZipFile(jar.toFile())) {
            assertNotNull(zip.getEntry("plugin.xml"),
                    "plugin.xml ontbreekt in de plugin-jar: " + jar);
            assertNotNull(zip.getEntry("org/bytedeco/javacpp/BytePointer.class"),
                    "bytedeco-javacpp is niet mee-geschad: " + jar);

            final String xml;
            try (var in = zip.getInputStream(zip.getEntry("plugin.xml"))) {
                xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            assertTrue(xml.contains("name=\"LLVM\""),
                    "De backend moet in de gebouwde jar als 'LLVM' geregistreerd staan:\n" + xml);
        }
    }
}

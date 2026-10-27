package io.github.potjerodekool.nabu.nativeimage;

import io.github.potjerodekool.nabu.nativeimage.NativeImageArguments.Action;
import io.github.potjerodekool.nabu.nativeimage.NativeImageArguments.UsageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeImageArgumentsTest {

    @TempDir
    Path temp;

    @Test
    void aMainClassGivesTheNameOfTheImage() throws Exception {
        final var arguments = NativeImageArguments.parse(List.of("-cp", "lib/a.jar" + File.pathSeparator + "classes",
                "com.acme.Tool"));

        assertEquals("com.acme.Tool", arguments.mainClass());
        assertEquals("tool", arguments.imageName());
        assertEquals(List.of(Path.of("lib/a.jar"), Path.of("classes")), arguments.classPath());
        assertEquals("BOEHM", arguments.gc());
        assertEquals("DEFAULT", arguments.opt());
        assertEquals(Action.BUILD, arguments.action());
    }

    @Test
    void anImageNameCanFollowTheClassOrComeFromOutputOption() throws Exception {
        assertEquals("one", NativeImageArguments.parse(List.of("a.Main", "one")).imageName());
        assertEquals("two", NativeImageArguments.parse(List.of("-o", "two", "a.Main")).imageName());
    }

    @Test
    void staticLinkingIsAnOption() throws Exception {
        assertTrue(NativeImageArguments.parse(List.of("--static", "a.Main")).staticLink());
        assertTrue(!NativeImageArguments.parse(List.of("a.Main")).staticLink());
    }

    @Test
    void optimizationAndGcOptions() throws Exception {
        final var arguments = NativeImageArguments.parse(List.of("-O3", "--gc=refcount", "a.Main"));

        assertEquals("AGGRESSIVE", arguments.opt());
        assertEquals("REFCOUNT", arguments.gc());
        assertEquals("NONE", NativeImageArguments.parse(List.of("-O0", "a.Main")).opt());
    }

    @Test
    void optionsOfGraalVmAreIgnoredWithAWarning() throws Exception {
        final var arguments = NativeImageArguments.parse(List.of("-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=a.B", "-march=native", "a.Main"));

        assertEquals(3, arguments.warnings().size());
        assertEquals("a.Main", arguments.mainClass());
    }

    @Test
    void aJarGivesTheMainClassTheClassPathAndTheName() throws Exception {
        final Path jar = temp.resolve("tool-1.0.jar");
        writeJar(jar, "app.Main", "lib/dep.jar other.jar");

        final var arguments = NativeImageArguments.parse(List.of("-jar", jar.toString()));

        assertEquals("app.Main", arguments.mainClass());
        assertEquals("tool-1.0", arguments.imageName());
        assertEquals(List.of(jar, temp.resolve("lib/dep.jar").normalize(), temp.resolve("other.jar").normalize()),
                arguments.classPath().stream().map(p -> p.toAbsolutePath().normalize()).toList());
    }

    @Test
    void aJarWithoutMainClassIsRefused() throws Exception {
        final Path jar = temp.resolve("lib.jar");
        writeJar(jar, null, null);

        final var e = assertThrows(UsageException.class, () -> NativeImageArguments.parse(List.of("-jar", jar.toString())));

        assertTrue(e.getMessage().contains("Main-Class"), e.getMessage());
    }

    @Test
    void wrongUsageIsExplained() {
        assertTrue(assertThrows(UsageException.class, () -> NativeImageArguments.parse(List.of())).getMessage()
                .contains("no main class"));
        assertTrue(assertThrows(UsageException.class, () -> NativeImageArguments.parse(List.of("--bogus", "a.Main")))
                .getMessage().contains("--bogus"));
        assertTrue(assertThrows(UsageException.class, () -> NativeImageArguments.parse(List.of("--gc", "x", "a.Main")))
                .getMessage().contains("--gc"));
        assertTrue(assertThrows(UsageException.class, () -> NativeImageArguments.parse(List.of("-cp")))
                .getMessage().contains("needs a value"));
        assertTrue(assertThrows(UsageException.class, () -> NativeImageArguments.parse(List.of("a.Main", "x", "y")))
                .getMessage().contains("unexpected"));
    }

    @Test
    void helpVersionAndDoctorNeedNoClass() throws Exception {
        assertEquals(Action.HELP, NativeImageArguments.parse(List.of("--help")).action());
        assertEquals(Action.VERSION, NativeImageArguments.parse(List.of("--version")).action());
        assertEquals(Action.DOCTOR, NativeImageArguments.parse(List.of("--doctor")).action());
    }

    @Test
    void aWildcardNamesTheJarsOfADirectory() throws Exception {
        Files.createDirectories(temp.resolve("libs"));
        Files.createFile(temp.resolve("libs/b.jar"));
        Files.createFile(temp.resolve("libs/a.jar"));
        Files.createFile(temp.resolve("libs/readme.txt"));

        final var arguments = NativeImageArguments.parse(List.of("-cp", temp.resolve("libs") + "/*", "a.Main"));

        assertEquals(List.of(temp.resolve("libs/a.jar"), temp.resolve("libs/b.jar")), arguments.classPath());
    }

    private static void writeJar(final Path jar, final String mainClass, final String classPath) throws IOException {
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        if (classPath != null) {
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        }
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // no entries: only the manifest matters
        }
    }
}

package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeObjectsTest {

    @TempDir
    Path temp;

    private String previousCacheDir;

    @BeforeEach
    void useACacheOfOurOwn() {
        previousCacheDir = System.getProperty("nabu.cache.dir");
        System.setProperty("nabu.cache.dir", temp.resolve("cache").toString());
    }

    @AfterEach
    void restore() {
        if (previousCacheDir == null) {
            System.clearProperty("nabu.cache.dir");
        } else {
            System.setProperty("nabu.cache.dir", previousCacheDir);
        }
        System.clearProperty("nabu.runtime.cache");
    }

    private Path source(final String name, final String body) throws Exception {
        final Path dir = Files.createDirectories(temp.resolve("src"));
        final Path file = dir.resolve(name);
        Files.writeString(file, body);
        return file;
    }

    @Test
    void theObjectIsCompiledOnceAndFoundAgain() throws Exception {
        final String gcc = NativeToolchain.assumeGcc();
        final Path c = source("part.c", "int part(void) { return 7; }\n");

        final List<Path> first = RuntimeObjects.get(List.of(c), gcc, List.of("-g"), "x");
        assertNotNull(first);
        assertEquals(1, first.size());
        assertTrue(Files.isRegularFile(first.get(0)));
        assertTrue(first.get(0).startsWith(temp.resolve("cache")), first.get(0).toString());
        final var stamp = Files.getLastModifiedTime(first.get(0));
        Thread.sleep(20);

        final List<Path> second = RuntimeObjects.get(List.of(c), gcc, List.of("-g"), "x");
        assertEquals(first, second);
        assertEquals(stamp, Files.getLastModifiedTime(second.get(0)), "compiled again");
    }

    @Test
    void anotherSourceFlagsOrTargetIsAnotherEntry() throws Exception {
        final String gcc = NativeToolchain.assumeGcc();
        final Path c = source("part.c", "int part(void) { return 1; }\n");
        final List<Path> one = RuntimeObjects.get(List.of(c), gcc, List.of("-g"), "x");

        Files.writeString(c, "int part(void) { return 2; }\n");
        final List<Path> changed = RuntimeObjects.get(List.of(c), gcc, List.of("-g"), "x");
        final List<Path> flags = RuntimeObjects.get(List.of(c), gcc, List.of("-g", "-DX"), "x");
        final List<Path> target = RuntimeObjects.get(List.of(c), gcc, List.of("-g"), "y");

        assertNotEquals(one, changed, "a changed source gives another entry");
        assertNotEquals(changed, flags, "other flags give another entry");
        assertNotEquals(changed, target, "another target gives another entry");
    }

    @Test
    void aSourceThatDoesNotCompileGivesNoObjectsAndLeavesNothingBehind() throws Exception {
        final String gcc = NativeToolchain.assumeGcc();
        final Path c = source("bad.c", "this is not C\n");

        assertNull(RuntimeObjects.get(List.of(c), gcc, List.of("-g"), "x"));

        try (var files = Files.walk(temp.resolve("cache"))) {
            assertEquals(List.of(), files.filter(p -> p.getFileName().toString().endsWith(".o")
                    || p.getFileName().toString().endsWith(".tmp")).toList());
        }
    }

    @Test
    void theCacheCanBeSwitchedOff() throws Exception {
        final String gcc = NativeToolchain.assumeGcc();
        final Path c = source("part.c", "int part(void) { return 7; }\n");
        System.setProperty("nabu.runtime.cache", "false");

        assertNull(RuntimeObjects.get(List.of(c), gcc, List.of("-g"), "x"));
    }

    @Test
    void theLinkCommandUsesTheObjectsInsteadOfTheSources() throws Exception {
        final List<String> cmd = Linker.buildCommand(List.of(Path.of("a.o")), Path.of("a.exe"),
                "x86_64-w64-windows-gnu", CompileOptions.GcStrategy.BOEHM,
                List.of(Path.of("rt1.o"), Path.of("rt2.o")));

        assertTrue(cmd.contains("rt1.o") && cmd.contains("rt2.o"), cmd.toString());
        assertTrue(cmd.stream().noneMatch(a -> a.endsWith(".c")), cmd.toString());
        assertTrue(!cmd.contains("-DNABU_GC_BOEHM"), "the define belongs to the compilation: " + cmd);
        assertTrue(cmd.contains("-lgc"), cmd.toString());
    }
}

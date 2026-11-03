package io.github.potjerodekool.nabu.maven;

import io.github.potjerodekool.nabu.compiler.client.DiagnosticEvent;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonCompilerTest {

    @Test
    void everyOptionGoesToTheDaemonByName() {
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.RELEASE, "21")
                .option(CompilerOption.CLASS_OUTPUT, "target/classes")
                .option(CompilerOption.INCREMENTAL, "false")
                .option(new CompilerOption("--system"), null)
                .build();

        final var map = DaemonCompiler.toOptionMap(options);

        assertEquals("21", map.get("--release"));
        assertEquals("target/classes", map.get("-d"));
        assertEquals("false", map.get("--incremental"));
        assertEquals("", map.get("--system"));
        assertEquals(4, map.size());
    }

    @Test
    void theIdentityOfTheJarChangesWhenTheJarIsBuiltAgain(@TempDir final Path dir) throws Exception {
        final var jar = dir.resolve("nabu-daemon.jar");
        Files.writeString(jar, "one");
        final var first = DaemonCompiler.daemonId(jar);

        Files.writeString(jar, "another build");
        final var second = DaemonCompiler.daemonId(jar);

        assertNotEquals(first, second);
        assertEquals(second, DaemonCompiler.daemonId(jar));
        assertTrue(first.contains("nabu-daemon.jar"));
    }

    @Test
    void theDaemonIsStartedWithItsPortAndIdentity() {
        final var command = DaemonCompiler.command(
                "java", List.of("-Xmx2g"), 9999, "the id", Path.of("daemon.jar"));

        assertEquals("java", command.get(0));
        assertEquals("-Xmx2g", command.get(1));
        assertTrue(command.contains("-Dnabu.daemon.port=9999"));
        assertTrue(command.contains("-Dnabu.daemon.id=the id"));
        assertEquals("-jar", command.get(command.size() - 2));
        assertTrue(command.get(command.size() - 1).endsWith("daemon.jar"));
    }

    @Test
    void aDiagnosticHasTheFormatOfMaven() {
        assertEquals("A.java:[3,7] Unexpected token", DaemonCompiler.format(
                new DiagnosticEvent(DiagnosticEvent.Kind.ERROR, "A.java", "Unexpected token", 3, 7)));
        assertEquals("A.java:[3] Unexpected token", DaemonCompiler.format(
                new DiagnosticEvent(DiagnosticEvent.Kind.ERROR, "A.java", "Unexpected token", 3, -1)));
        assertEquals("A.java Unexpected token", DaemonCompiler.format(
                new DiagnosticEvent(DiagnosticEvent.Kind.ERROR, "A.java", "Unexpected token", -1, -1)));
        assertEquals("Something went wrong", DaemonCompiler.format(
                new DiagnosticEvent(DiagnosticEvent.Kind.ERROR, "", "Something went wrong", -1, -1)));
    }
}

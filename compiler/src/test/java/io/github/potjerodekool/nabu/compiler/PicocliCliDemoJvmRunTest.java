package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * De cli-demo (vendored picocli + greet) via de ASM-backend op de JVM: dezelfde
 * vijf acceptatiecommando's als de native build moeten dezelfde output en
 * exitcode geven. Overgeslagen als de demo-repository niet aanwezig is.
 */
class PicocliCliDemoJvmRunTest {

    private static final Path DEMO = Path.of("C:\\projects\\incurbation\\cli-demo");

    @TempDir
    Path tempDir;

    @Test
    void acceptanceCommandsOnTheJvm() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(DEMO.resolve("vendor/picocli-src")),
                "cli-demo niet aanwezig");
        final var out = tempDir.resolve("out");
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, "C:\\projects\\nabu\\compiler\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, DEMO.resolve("vendor/picocli-src") + ";" + DEMO.resolve("src/main/nabu"))
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("gen").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .build();
        final var compiler = new NabuCompiler();
        final var errors = new AtomicInteger();
        final var messages = new java.util.ArrayList<String>();
        compiler.setListener(d -> {
            if (d.getKind().name().equals("ERROR")) {
                errors.incrementAndGet();
                messages.add(d.getMessage(java.util.Locale.ROOT).toString());
            }
        });
        assertEquals(0, compiler.compile(options) + errors.get(), String.join("\n", messages));

        final var nl = System.lineSeparator();
        final var usage = "Usage: greet [-hV] [-c=<count>] [-n=<name>]" + nl
                + "Says hello to someone" + nl
                + "  -c, --count=<count>   Number of greetings" + nl
                + "  -h, --help            Show this help message and exit." + nl
                + "  -n, --name=<name>     Name to greet" + nl
                + "  -V, --version         Print version information and exit." + nl;
        // ANSI uit, zoals de acceptatie-eis: anders volgt picocli de omgeving
        // (TERM/OSTYPE in Git Bash zet kleuren aan).
        final var previousAnsi = System.setProperty("picocli.ansi", "false");
        try (var loader = new URLClassLoader(new java.net.URL[]{out.toUri().toURL()}, null)) {
            assertEquals("0|Hello world, 1 times" + nl, run(loader));
            assertEquals("0|" + usage, run(loader, "--help"));
            assertEquals("0|greet 1.0" + nl, run(loader, "--version"));
            assertEquals("0|Hello Ada, 3 times" + nl, run(loader, "-n", "Ada", "-c", "3"));
            assertEquals("2|Unknown option: '--bogus'" + nl + usage, run(loader, "--bogus"));
        } finally {
            if (previousAnsi == null) {
                System.clearProperty("picocli.ansi");
            } else {
                System.setProperty("picocli.ansi", previousAnsi);
            }
        }
    }

    /** Voert {@code new CommandLine(new GreetCommand()).execute(args)} uit; "exitcode|output". */
    private static String run(final ClassLoader loader, final String... args) throws Exception {
        final var commandLine = loader.loadClass("picocli.CommandLine");
        final var command = loader.loadClass("greet.GreetCommand").getConstructor().newInstance();
        final var buffer = new ByteArrayOutputStream();
        final PrintStream oldOut = System.out;
        final PrintStream oldErr = System.err;
        final var capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            final Object line = commandLine.getConstructor(Object.class).newInstance(command);
            final int exit = (int) commandLine.getMethod("execute", String[].class).invoke(line, (Object) args);
            return exit + "|" + buffer.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }
}

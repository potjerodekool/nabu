package io.github.potjerodekool.nabu.compiler.daemon;

import io.github.potjerodekool.nabu.compiler.client.DaemonEvent;
import io.github.potjerodekool.nabu.compiler.client.LightweightClient;
import io.github.potjerodekool.nabu.compiler.client.Status;
import io.github.potjerodekool.nabu.compiler.client.StatusEvent;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonProtocolTest {

    private static LightweightCompilerDaemon daemon;
    private static LightweightClient client;

    @BeforeAll
    static void startDaemon() throws Exception {
        System.setProperty("nabu.daemon.id", "test-daemon");
        // Port 0: a free port, so the test cannot collide with a daemon that is really running.
        daemon = new LightweightCompilerDaemon(0);
        final var thread = new Thread(() -> {
            try {
                daemon.start();
            } catch (final Exception ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        client = new LightweightClient(daemon.getPort());
    }

    @AfterAll
    static void stopDaemon() {
        daemon.stop();
    }

    @Test
    void pingReportsTheIdentityOfTheDaemon() throws Exception {
        assertEquals(Optional.of("test-daemon"), client.pingMessage());
        assertTrue(client.ping());
    }

    @Test
    void everyOptionOfTheClientReachesTheCompiler() {
        final var options = CompileTaskHandler.toCompilerOptions(Map.of(
                "--release", "21",
                "-d", "target/classes",
                "--class-path", "a.jar" + java.io.File.pathSeparator + "b.jar",
                "--incremental", "false",
                "--processor-path", "p.jar",
                "--backend", "ASM",
                "--an-option-of-a-plugin", "x"
        ));

        assertEquals(Optional.of("21"), options.getOption(CompilerOption.RELEASE));
        assertEquals(Optional.of("target/classes"), options.getOption(CompilerOption.CLASS_OUTPUT));
        assertEquals(Optional.of("false"), options.getOption(CompilerOption.INCREMENTAL));
        assertEquals(Optional.of("p.jar"), options.getOption(CompilerOption.ANNOTATION_PROCESSOR_PATH));
        assertEquals(Optional.of("ASM"), options.getOption(CompilerOption.BACKEND));
        assertEquals(Optional.of("x"), options.getOption(new CompilerOption("--an-option-of-a-plugin")));
        // The class path is not changed: the compilation is the same as one in the process of the client.
        assertEquals(Optional.of("a.jar" + java.io.File.pathSeparator + "b.jar"),
                options.getOption(CompilerOption.CLASS_PATH));
    }

    @Test
    void withoutClassPathAndOutputTheOldDefaultsApply() {
        final var options = CompileTaskHandler.toCompilerOptions(Map.of("--source-path", "src"));

        assertEquals(Optional.of("out"), options.getOption(CompilerOption.CLASS_OUTPUT));
        assertEquals(Optional.of("out"), options.getOption(CompilerOption.CLASS_PATH));
    }

    @Test
    void anOptionLongerThan64KilobytesSurvivesTheTransport() throws Exception {
        // writeUTF stops at 64 KB; the class path of a big project is longer. There is no source path, so the
        // daemon answers without starting a compilation, but it has to read every byte of the option for that.
        final var classPath = ("x".repeat(99) + java.io.File.pathSeparator).repeat(1_000);
        assertTrue(classPath.length() > 70_000);

        final List<DaemonEvent> events = new ArrayList<>();
        final var status = client.compile(Map.of("--class-path", classPath), events::add);

        assertEquals(Status.END, status);
        assertTrue(events.contains(new StatusEvent(Status.END, "OK")), "events: " + events);
    }
}

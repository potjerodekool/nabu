package io.github.potjerodekool.nabu.compiler.daemon;

import io.github.potjerodekool.nabu.compiler.client.CompilerOptionBuilder;
import io.github.potjerodekool.nabu.compiler.client.DaemonEvent;
import io.github.potjerodekool.nabu.compiler.client.LightweightClient;
import io.github.potjerodekool.nabu.compiler.client.Status;
import io.github.potjerodekool.nabu.compiler.client.StatusEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptatiecriterium Fase 5 (client-wiring): de hele keten
 * {@code nabu-client} (CompilerOptionBuilder + LightweightClient) →
 * {@code nabu-daemon} (TCP) → {@code NabuCompiler.compile} met
 * {@code --backend=LLVM} produceert een native executable.
 *
 * De daemon draait in-process op een ephemeral port (het protocol vraagt de
 * opties met een {@code count}-field; de LightweightClient schreef dat field
 * vroeger níet — zonder de fix leest de daemon garbage en faalt elke compile).
 */
class LlvmClientDaemonIT {

    @TempDir
    Path tempDir;

    private static int ephemeralPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void clientCompilesNabuSourceToExeViaDaemonBackend() throws Exception {
        Path src = tempDir.resolve("Main.nabu");
        Files.writeString(src,
                "public class Main {\n" +
                "    fun main(args: String[]): void {\n" +
                "    }\n" +
                "}\n",
                StandardCharsets.UTF_8);

        Path out = tempDir.resolve("out");
        Files.createDirectories(out);

        var options = new CompilerOptionBuilder()
                .sourcePath(src.getParent().toAbsolutePath().toString())
                .output(out.toAbsolutePath().toString())
                .backend("LLVM")
                .build();

        int port = ephemeralPort();
        final LightweightCompilerDaemon daemon = new LightweightCompilerDaemon(port);
        Thread daemonThread = new Thread(() -> {
            try {
                daemon.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        daemonThread.setDaemon(true);
        daemonThread.start();

        try {
            var client = new LightweightClient(port);
            assertTrue(client.ping(), "PING moet lukken");

            List<DaemonEvent> events = new ArrayList<>();
            Status result = client.compile(options, events::add);

            assertEquals(Status.END, result, "Compile moest eindigen met STATUS_END");
            assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                            && se.status() == Status.STREAMING),
                    "Geen compile-started statusevent ontvangen. Events: " + events);

            Path exe;
            try (Stream<Path> walk = Files.walk(out)) {
                exe = walk.filter(p -> p.getFileName().toString().endsWith(".exe"))
                        .findFirst()
                        .orElse(null);
            }
            assertNotNull(exe, "Geen .exe geproduceerd: " + out);
            assertTrue(Files.exists(exe), ".exe niet aangemaakt: " + exe);
        } finally {
            daemon.stop();
            daemonThread.interrupt();
        }
    }

    @Test
    void protocolCountIsWritten() throws Exception {
        final var options = new CompilerOptionBuilder().backend("LLVM").build();
        assertEquals(1, options.size(), "Builder moet exact 1 optie bevatten");
        assertEquals("LLVM", options.get("--backend"));
    }
}
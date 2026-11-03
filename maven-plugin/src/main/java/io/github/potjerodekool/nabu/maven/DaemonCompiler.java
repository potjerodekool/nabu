package io.github.potjerodekool.nabu.maven;

import io.github.potjerodekool.nabu.compiler.client.ByteCodeEvent;
import io.github.potjerodekool.nabu.compiler.client.DaemonEvent;
import io.github.potjerodekool.nabu.compiler.client.DiagnosticEvent;
import io.github.potjerodekool.nabu.compiler.client.LightweightClient;
import io.github.potjerodekool.nabu.compiler.client.Status;
import io.github.potjerodekool.nabu.compiler.client.StatusEvent;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.apache.maven.plugin.logging.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Compiles with the Nabu compiler daemon: a JVM that stays alive between builds, so the classes of the compiler
 * (and the tables ANTLR builds lazily while parsing) are still warm in the next build. Maven creates the class loader
 * of a plugin again for every build, even in mvnd, so inside the plugin nothing can stay warm.
 * <p>
 * The daemon is started when it is not running, and restarted when it is another build of the daemon jar. When
 * anything about the daemon fails the caller compiles in its own process instead ({@link Outcome#UNAVAILABLE}).
 */
final class DaemonCompiler {

    enum Outcome {
        /** The daemon compiled without errors. */
        SUCCESS,
        /** The daemon compiled and reported errors. */
        FAILED,
        /** There is no (usable) daemon: compile in the process of Maven. */
        UNAVAILABLE
    }

    private static final long START_TIMEOUT_MS = 30_000;
    private static final long STOP_TIMEOUT_MS = 10_000;
    private static final long POLL_INTERVAL_MS = 200;

    private final Log log;
    private final int port;
    private final Path daemonJar;
    private final String javaExecutable;
    private final List<String> jvmArgs;
    private final LightweightClient client;

    DaemonCompiler(final Log log,
                   final int port,
                   final Path daemonJar,
                   final List<String> jvmArgs) {
        this(log, port, daemonJar, defaultJavaExecutable(), jvmArgs);
    }

    DaemonCompiler(final Log log,
                   final int port,
                   final Path daemonJar,
                   final String javaExecutable,
                   final List<String> jvmArgs) {
        this.log = log;
        this.port = port;
        this.daemonJar = daemonJar;
        this.javaExecutable = javaExecutable;
        this.jvmArgs = jvmArgs;
        this.client = new LightweightClient(port);
    }

    Outcome compile(final CompilerOptions options) {
        try {
            if (!ensureDaemon()) {
                return Outcome.UNAVAILABLE;
            }

            final var end = new AtomicReference<String>();
            client.compile(toOptionMap(options), event -> handleEvent(event, end));

            // No end message: the connection broke, and nothing is known about the result.
            if (end.get() == null) {
                log.warn("The Nabu daemon did not report the result of the compilation");
                return Outcome.UNAVAILABLE;
            }

            return "OK".equals(end.get()) ? Outcome.SUCCESS : Outcome.FAILED;
        } catch (final IOException | RuntimeException e) {
            log.warn("The Nabu daemon on port " + port + " failed (" + e + ")");
            return Outcome.UNAVAILABLE;
        }
    }

    /**
     * @return The options as the daemon wants them: option name and value.
     */
    static Map<String, String> toOptionMap(final CompilerOptions options) {
        final var map = new LinkedHashMap<String, String>();
        options.forEach((option, value) -> map.put(option.optionName(), value == null ? "" : value));
        return map;
    }

    /**
     * @return The identity of a daemon jar. A daemon that reports another identity is another build of the jar (a
     * snapshot that was built again has the same version), so it is stopped and the jar is started again.
     */
    static String daemonId(final Path jar) throws IOException {
        return jar.toAbsolutePath().normalize() + "|" + Files.getLastModifiedTime(jar).toMillis() + "|" + Files.size(jar);
    }

    static List<String> command(final String javaExecutable,
                                final List<String> jvmArgs,
                                final int port,
                                final String daemonId,
                                final Path daemonJar) {
        final var command = new ArrayList<String>();
        command.add(javaExecutable);
        command.addAll(jvmArgs);
        command.add("-Dnabu.daemon.port=" + port);
        command.add("-Dnabu.daemon.id=" + daemonId);
        command.add("-jar");
        command.add(daemonJar.toAbsolutePath().toString());
        return command;
    }

    private static String defaultJavaExecutable() {
        final var executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", executable).toString();
    }

    private boolean ensureDaemon() throws IOException {
        final var expectedId = daemonId(daemonJar);
        final var running = ping();

        if (running.isPresent()) {
            if (running.get().equals(expectedId)) {
                return true;
            }

            log.info("The Nabu daemon on port " + port + " is another build, restarting it");
            client.shutdown();

            if (!waitUntil(() -> ping().isEmpty(), STOP_TIMEOUT_MS)) {
                log.warn("The Nabu daemon on port " + port + " did not stop");
                return false;
            }
        }

        return start(expectedId);
    }

    private Optional<String> ping() {
        try {
            return client.pingMessage();
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    private boolean start(final String expectedId) throws IOException {
        // The daemon runs in the directory of Nabu, not in the project: a process in a directory blocks deleting it.
        final var home = Paths.get(System.getProperty("user.home"), ".nabu");
        Files.createDirectories(home);
        final var logFile = home.resolve("daemon-" + port + ".log");

        log.info("Starting the Nabu daemon on port " + port + " (log: " + logFile + ")");

        final var process = new ProcessBuilder(command(javaExecutable, jvmArgs, port, expectedId, daemonJar))
                .directory(home.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();

        final var started = waitUntil(() -> ping().filter(expectedId::equals).isPresent() || !process.isAlive(),
                START_TIMEOUT_MS);

        if (!started || !process.isAlive()) {
            log.warn("The Nabu daemon did not start, see " + logFile);
            return false;
        }

        return true;
    }

    private boolean waitUntil(final BooleanSupplier condition,
                              final long timeoutMs) {
        final var deadline = System.nanoTime() + timeoutMs * 1_000_000;

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return condition.getAsBoolean();
    }

    private void handleEvent(final DaemonEvent event,
                             final AtomicReference<String> end) {
        if (event instanceof DiagnosticEvent diagnostic) {
            final var message = format(diagnostic);

            switch (diagnostic.kind()) {
                case ERROR -> log.error(message);
                case WARN, MANDATORY_WARNING -> log.warn(message);
                default -> log.info(message);
            }
        } else if (event instanceof ByteCodeEvent byteCode) {
            log.debug("Generated " + byteCode.className());
        } else if (event instanceof StatusEvent status && status.status() == Status.END) {
            end.set(status.message());
        }
    }

    /**
     * @return A diagnostic in the format of Maven: {@code file:[line,column] message}.
     */
    static String format(final DiagnosticEvent diagnostic) {
        final var location = new StringBuilder();

        if (diagnostic.fileName() != null && !diagnostic.fileName().isEmpty()) {
            location.append(diagnostic.fileName());

            if (diagnostic.lineNumber() >= 0) {
                location.append(":[").append(diagnostic.lineNumber());

                if (diagnostic.columnNumber() >= 0) {
                    location.append(',').append(diagnostic.columnNumber());
                }

                location.append(']');
            }

            location.append(' ');
        }

        return location + diagnostic.message();
    }
}

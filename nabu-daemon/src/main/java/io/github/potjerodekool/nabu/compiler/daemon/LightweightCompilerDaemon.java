package io.github.potjerodekool.nabu.compiler.daemon;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Lightweight Compiler Daemon with custom TCP protocol
 */
public class LightweightCompilerDaemon {
    private static final Logger LOGGER = Logger.getLogger(LightweightCompilerDaemon.class.getName());
    private static final int PORT = 9876;
    private static final int MAX_CONNECTIONS = 10;
    private static final long IDLE_TIMEOUT_MS = Long.getLong(
            "nabu.daemon.idleTimeoutMs",
            TimeUnit.MINUTES.toMillis(30)
    );

    private final ExecutorService executorService;
    private final ServerSocket serverSocket;
    private volatile boolean running = true;
    private volatile long lastActivity = System.currentTimeMillis();

    public LightweightCompilerDaemon() throws IOException {
        this(PORT);
    }

    public LightweightCompilerDaemon(final int port) throws IOException {
        this.executorService = Executors.newFixedThreadPool(MAX_CONNECTIONS);
        this.serverSocket = new ServerSocket(port);
    }

    public static void main(String[] args) throws IOException {
        LightweightCompilerDaemon daemon = new LightweightCompilerDaemon();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Received shutdown signal...");
            daemon.stop();
        }));

        try {
            daemon.start();
        } catch (IOException e) {
            LOGGER.severe("Error while starting: " + e.getMessage());
            System.exit(1);
        }
    }

    public void start() throws IOException {
        LOGGER.info("╔════════════════════════════════════════════════╗");
        LOGGER.info("║  Lightweight Compiler Daemon                   ║");
        LOGGER.info("╠════════════════════════════════════════════════╣");
        LOGGER.info("║  Protocol: Custom TCP Binary                   ║");
        LOGGER.info("║  Port: " + PORT + "                            ║");
        LOGGER.info("║  Status: RUNNING                               ║");
        LOGGER.info("╚════════════════════════════════════════════════╝");

        startIdleWatcher();

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                lastActivity = System.currentTimeMillis();
                LOGGER.info("New connection of: " + clientSocket.getInetAddress());
                executorService.execute(new ClientHandler(this, clientSocket));
            } catch (SocketException e) {
                if (running) {
                    LOGGER.warning("Socket exception: " + e.getMessage());
                }
            }
        }
    }

    private void startIdleWatcher() {
        final var watcher = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (System.currentTimeMillis() - lastActivity > IDLE_TIMEOUT_MS) {
                    LOGGER.info("No activity for " + IDLE_TIMEOUT_MS + " ms, shutting down daemon");
                    stop();
                    System.exit(0);
                }
            }
        }, "nabu-daemon-idle-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            executorService.shutdown();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
            LOGGER.info("Daemon stopt");
        } catch (Exception e) {
            LOGGER.severe("Error while stopping: " + e.getMessage());
        }
    }

}

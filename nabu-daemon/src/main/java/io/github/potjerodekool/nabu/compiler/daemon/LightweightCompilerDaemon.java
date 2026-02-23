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

    private final ExecutorService executorService;
    private ServerSocket serverSocket;
    private volatile boolean running = true;

    public LightweightCompilerDaemon() {
        this.executorService = Executors.newFixedThreadPool(MAX_CONNECTIONS);
    }

    public static void main(String[] args) {
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
        serverSocket = new ServerSocket(PORT);
        LOGGER.info("╔════════════════════════════════════════════════╗");
        LOGGER.info("║  Lightweight Compiler Daemon                   ║");
        LOGGER.info("╠════════════════════════════════════════════════╣");
        LOGGER.info("║  Protocol: Custom TCP Binary                   ║");
        LOGGER.info("║  Port: " + PORT + "                            ║");
        LOGGER.info("║  Status: RUNNING                               ║");
        LOGGER.info("╚════════════════════════════════════════════════╝");

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                LOGGER.info("New connection of: " + clientSocket.getInetAddress());
                executorService.execute(new ClientHandler(this, clientSocket));
            } catch (SocketException e) {
                if (running) {
                    LOGGER.warning("Socket exception: " + e.getMessage());
                }
            }
        }
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

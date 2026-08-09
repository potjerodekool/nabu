package io.github.potjerodekool.nabu.compiler.daemon;

import io.github.potjerodekool.nabu.tools.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler voor client connecties
 */
class ClientHandler implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());

    private final LightweightCompilerDaemon lightweightCompilerDaemon;
    private final Socket socket;

    private final CompileTaskHandler compileTaskHandler = new CompileTaskHandler();
    private final PingTaskHandler  pingTaskHandler = new PingTaskHandler();

    public ClientHandler(final LightweightCompilerDaemon lightweightCompilerDaemon,
                         final Socket socket) {
        this.lightweightCompilerDaemon = lightweightCompilerDaemon;
        this.socket = socket;
    }

    @Override
    public void run() {
        try (final DataInputStream in = new DataInputStream(socket.getInputStream());
             final DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            // Lees command byte
            byte command = in.readByte();

            LOGGER.info(String.format("Command ontvangen: 0x%02X van %s",
                    command, socket.getInetAddress()));

            switch (command) {
                case Protocol.CMD_COMPILE -> compileTaskHandler.handle(in, out);
                case Protocol.CMD_PING -> pingTaskHandler.handle(in, out);
                case Protocol.CMD_SHUTDOWN ->
                    handleShutdown(out);
                default ->
                    sendError(out, "Onbekend command: 0x" +
                            String.format("%02X", command));
            }

        } catch (EOFException e) {
            LOGGER.info("Client gesloten: " + socket.getInetAddress());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,"Fout bij verwerken request: " + e.getMessage(), e);
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Negeer
            }
        }
    }

    private void handleShutdown(final DataOutputStream out) throws IOException {
        LOGGER.info("Shutdown command ontvangen");
        sendSuccess(out, "Shutting down...");

        // Stop daemon in separate thread
        new Thread(() -> {
            try {
                Thread.sleep(100); // Give some time to send response.
                lightweightCompilerDaemon.stop();
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void sendSuccess(final DataOutputStream out,
                             final String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        out.writeByte(Protocol.STATUS_SUCCESS);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    private void sendError(final DataOutputStream out,
                           final String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        out.writeByte(Protocol.STATUS_ERROR);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

}

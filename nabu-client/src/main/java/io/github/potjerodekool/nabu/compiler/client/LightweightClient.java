package io.github.potjerodekool.nabu.compiler.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Client for lightweight compiler daemon
 * Uses custom TCP binary protocol
 */
public class LightweightClient {
    private static final String HOST = "localhost";
    private static final int PORT = 9876;

    private void performAction(final Action consumer) throws IOException {
        try (final Socket socket = new Socket(HOST, PORT)) {
            final var out = new DataOutputStream(socket.getOutputStream());
            final var in = new DataInputStream(socket.getInputStream());
            consumer.apply(in, out);
        }
    }

    public void ping(final Consumer<CheckEvent> listener) throws IOException {
        performAction((in, out) -> {
            // Send PING command
            out.writeByte(Protocol.CMD_PING);
            out.flush();

            // read response
            byte status = in.readByte();
            int length = in.readInt();
            byte[] data = new byte[length];
            in.readFully(data);

            String response = new String(data, StandardCharsets.UTF_8);

            if (status == Protocol.STATUS_SUCCESS) {
                listener.accept(new CheckEvent(CheckEvent.Kind.PING, response));
            }
        });
    }

    public void compile(final Map<String, String> compilerOptions,
                        final Consumer<DaemonEvent> listener) throws IOException {
        performAction((in, out) -> {
            // Send COMPILE command
            out.writeByte(Protocol.CMD_COMPILE);

            //Set compiler options.
            for (final var compileOption : compilerOptions.entrySet()) {
                final var key = compileOption.getKey();
                final var value = compileOption.getValue();
                final var option = String.format("%s %s", key, value);
                out.writeUTF(option);
            }
            out.flush();

            // Read streaming response
            readStreamingResponse(in, listener);
        });
    }

    public void shutdown() throws IOException {
        performAction((in, out) -> {
            out.writeByte(Protocol.CMD_SHUTDOWN);
            out.flush();

            byte status = in.readByte();
            int length = in.readInt();
            byte[] data = new byte[length];
            in.readFully(data);

            System.out.println(new String(data, StandardCharsets.UTF_8));
        });
    }

    private void readStreamingResponse(final DataInputStream in,
                                       final Consumer<DaemonEvent> listener) throws IOException {
        boolean readResponses = true;

        do {
            byte type = in.readByte();

            if (type >= Protocol.STATUS_SUCCESS && type <= Protocol.STATUS_END) {
                final var status = sendStatusEvent(type, in, listener);
                if (status == Status.END || status == Status.ERROR) {
                    readResponses = false;
                }
            } else if (type >= Protocol.DIAGNOSTIC_ERROR && type <= Protocol.DIAGNOSTIC_OTHER) {
                sengDiagnostic(type, in, listener);
            }
        } while (readResponses);
    }

    private Status sendStatusEvent(final int type,
                                   final DataInputStream in,
                                   final Consumer<DaemonEvent> listener) throws IOException {
        int length = in.readInt();
        byte[] data = new byte[length];
        in.readFully(data);
        final var message = new String(data);
        final Status status;

        status = switch (type) {
            case Protocol.STATUS_SUCCESS -> Status.SUCCESS;
            case Protocol.STATUS_ERROR -> Status.ERROR;
            case Protocol.STATUS_COMPILE_STARTED -> Status.STREAMING;
            case Protocol.STATUS_END -> Status.END;
            default -> null;
        };

        try {
            listener.accept(new StatusEvent(status, message));
        } catch (final Throwable ignored) {
        }
        return status;
    }

    private void sengDiagnostic(final int type,
                                final DataInputStream in,
                                final Consumer<DaemonEvent> listener) throws IOException {
        final var fileName = readData(in);
        final var message = readData(in);

        final var kind = switch (type) {
            case Protocol.DIAGNOSTIC_ERROR -> DiagnosticEvent.Kind.ERROR;
            case Protocol.DIAGNOSTIC_WARN -> DiagnosticEvent.Kind.WARN;
            case Protocol.DIAGNOSTIC_MANDATORY_WARNING -> DiagnosticEvent.Kind.MANDATORY_WARNING;
            case Protocol.DIAGNOSTIC_NOTE -> DiagnosticEvent.Kind.NOTE;
            default -> DiagnosticEvent.Kind.OTHER;
        };

        listener.accept(new DiagnosticEvent(kind, fileName, message));
    }

    private String readData(final DataInputStream in) throws IOException {
        final var length = in.readInt();
        final var data = new byte[length];
        in.readFully(data);
        return new String(data);
    }

}

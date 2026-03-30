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

    private <R> R performAction(final ActionWithResult<R> consumer) throws IOException {
        try (final Socket socket = new Socket(HOST, PORT)) {
            final var out = new DataOutputStream(socket.getOutputStream());
            final var in = new DataInputStream(socket.getInputStream());
            return consumer.apply(in, out);
        }
    }

    public boolean ping() throws IOException {
        return performAction((in, out) -> {
            // Send PING command
            out.writeByte(Protocol.CMD_PING);
            out.flush();

            // read response
            byte status = in.readByte();
            int length = in.readInt();
            byte[] data = new byte[length];
            in.readFully(data);

            return status == Protocol.STATUS_SUCCESS;
        });
    }

    public Status compile(final Map<String, String> compilerOptions,
                        final Consumer<DaemonEvent> listener) throws IOException {
        return performAction((in, out) -> {
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
            return readStreamingResponse(in, listener);
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

    private Status readStreamingResponse(final DataInputStream in,
                                       final Consumer<DaemonEvent> listener) throws IOException {
        boolean readResponses = true;
        Status resultStatus = Status.END;

        do {
            byte type = in.readByte();

            if (type >= Protocol.STATUS_SUCCESS && type <= Protocol.STATUS_END) {
                final var status = sendStatusEvent(type, in, listener);
                if (status == Status.END || status == Status.ERROR) {
                    readResponses = false;
                }
                resultStatus = status;
            } else if (type == Protocol.BYTECODE_GENERATED) {
                sendBytecodeGenerated(in, listener);
            } else if (type >= Protocol.DIAGNOSTIC_ERROR && type <= Protocol.DIAGNOSTIC_OTHER) {
                sendDiagnostic(type, in, listener);
            }
        } while (readResponses);

        return resultStatus;
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

    private void sendDiagnostic(final int type,
                                final DataInputStream in,
                                final Consumer<DaemonEvent> listener) throws IOException {
        final var fileName = readData(in);
        final var message = readData(in);
        //final var lineNumber = in.readInt();
        //final var columnNumber = in.readInt();
        final var lineNumber = -1;
        final var columnNumber = -1;

        final var kind = switch (type) {
            case Protocol.DIAGNOSTIC_ERROR -> DiagnosticEvent.Kind.ERROR;
            case Protocol.DIAGNOSTIC_WARN -> DiagnosticEvent.Kind.WARN;
            case Protocol.DIAGNOSTIC_MANDATORY_WARNING -> DiagnosticEvent.Kind.MANDATORY_WARNING;
            case Protocol.DIAGNOSTIC_NOTE -> DiagnosticEvent.Kind.NOTE;
            default -> DiagnosticEvent.Kind.OTHER;
        };

        listener.accept(new DiagnosticEvent(kind, fileName, message, lineNumber, columnNumber));
    }

    private void sendBytecodeGenerated(final DataInputStream in,
                                       final Consumer<DaemonEvent> listener) throws IOException {
        final var sourceFileName = readData(in);
        final var classFileName = readData(in);
        final var className = readData(in);

        listener.accept(new ByteCodeEvent(sourceFileName, classFileName, className));
    }

    private String readData(final DataInputStream in) throws IOException {
        final var length = in.readInt();
        final var data = new byte[length];
        in.readFully(data);
        return new String(data);
    }

}

package io.github.potjerodekool.nabu.compiler.daemon;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic;

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


    private final byte[] NO_DATA = new byte[0];

    private final LightweightCompilerDaemon lightweightCompilerDaemon;
    private final Socket socket;

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
                case Protocol.CMD_COMPILE:
                    handleCompile(in, out);
                    break;
                case Protocol.CMD_PING:
                    handlePing(out);
                    break;
                case Protocol.CMD_SHUTDOWN:
                    handleShutdown(out);
                    break;
                default:
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

    private String readUTF(final DataInputStream inputStream) throws IOException {
        if (inputStream.available() > 0) {
            return inputStream.readUTF();
        } else {
            return null;
        }
    }

    private Map<String, String> readCompileOptions(final DataInputStream in) throws IOException {
        final var options = new HashMap<String, String>();

        String option;

        while ((option = readUTF(in)) != null) {
            final var sep = option.indexOf(' ');
            final var key = option.substring(0, sep);
            final var value = option.substring(sep + 1);
            options.put(key, value);
        }

        return options;
    }

    private void handleCompile(final DataInputStream in,
                               final DataOutputStream out) throws IOException {
        final var optionsMap = readCompileOptions(in);
        sendCompileStarted(out);

        final var nabuCompiler = new NabuCompiler();
        final var compilerOptionsBuilder = new CompilerOptions.CompilerOptionsBuilder();

        configureClassPath(compilerOptionsBuilder, optionsMap);
        configureSourceRoots(compilerOptionsBuilder, optionsMap);

        final var outputDirectory = optionsMap.get(CompilerOption.CLASS_OUTPUT.optionName());

        compilerOptionsBuilder.option(CompilerOption.CLASS_OUTPUT, outputDirectory);

        nabuCompiler.setListener(diagnostic -> {
            try {
                sendDiagnostic(diagnostic, out);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });

        final var result = nabuCompiler.compile(compilerOptionsBuilder.build());
        sendEnd(out, result == 0);
    }

    private void configureClassPath(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder,
                                    final Map<String, String> optionsMap) {
        final var outputDirectory = optionsMap.get(CompilerOption.CLASS_OUTPUT.optionName());
        final var classPath = optionsMap.get(CompilerOption.CLASS_PATH.optionName());
        final var classPathEntries = Arrays.asList(classPath.split(File.pathSeparator));

        final var paths = new ArrayList<String>();
        paths.add(outputDirectory);
        paths.addAll(classPathEntries);
        compilerOptionsBuilder.option(
                CompilerOption.CLASS_PATH,
                String.join(File.pathSeparator, paths)
        );
    }

    private void configureSourceRoots(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder,
                                      final Map<String, String> optionsMap) {
        final var sourceRoots = optionsMap.get(CompilerOption.SOURCE_PATH.optionName());
        final var sourcePath = String.join(File.pathSeparator, sourceRoots);
        compilerOptionsBuilder.option(CompilerOption.SOURCE_PATH, sourcePath);
    }

    private void handlePing(final DataOutputStream out) throws IOException {
        long timestamp = System.currentTimeMillis();
        sendSuccess(out, "PONG " + timestamp);
    }

    private void handleShutdown(final DataOutputStream out) throws IOException {
        LOGGER.info("Shutdown command ontvangen");
        sendSuccess(out, "Shutting down...");

        // Stop daemon in aparte thread
        new Thread(() -> {
            try {
                Thread.sleep(100); // Geef tijd om response te verzenden
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

    private void sendDiagnostic(final Diagnostic diagnostic,
                                final DataOutputStream out) throws IOException {
        final var diagnosticCode = switch (diagnostic.getKind()) {
            case ERROR -> Protocol.DIAGNOSTIC_ERROR;
            case WARN -> Protocol.DIAGNOSTIC_WARN;
            case MANDATORY_WARNING -> Protocol.DIAGNOSTIC_MANDATORY_WARNING;
            case NOTE -> Protocol.DIAGNOSTIC_NOTE;
            case OTHER -> Protocol.DIAGNOSTIC_OTHER;
        };

        final var file = diagnostic.getFileObject();
        final var fileName = file != null ? toByteArray(file.getFileName()) : NO_DATA;
        final var message = toByteArray(diagnostic.getMessage(null));

        out.writeByte(diagnosticCode);
        out.writeInt(fileName.length);
        out.write(fileName);
        out.writeInt(message.length);
        out.write(message);
        out.flush();
    }

    private byte[] toByteArray(final CharSequence value) {
        return value != null ? toByteArray(value.toString()) : NO_DATA;
    }

    private byte[] toByteArray(final String value) {
        return value != null ? value.getBytes(StandardCharsets.UTF_8) : NO_DATA;
    }

    private void sendCompileStarted(final DataOutputStream out) throws IOException {
        final var message = "=== COMPILATIE GESTART ===";
        byte[] data = (message + "\n").getBytes(StandardCharsets.UTF_8);
        out.writeByte(Protocol.STATUS_COMPILE_STARTED);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    private void sendEnd(final DataOutputStream out,
                         final boolean success) throws IOException {
        String message = success ? "OK" : "ERROR";
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        out.writeByte(Protocol.STATUS_END);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }
}

package io.github.potjerodekool.nabu.compiler.daemon;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class CompileTaskHandler implements TaskHandler {

    private final byte[] NO_DATA = new byte[0];

    @Override
    public void handle(final DataInputStream in,
                       final DataOutputStream out) throws IOException {
        final var optionsMap = readCompileOptions(in);
        sendCompileStarted(out);

        final var nabuCompiler = new NabuCompiler();
        final var options = toCompilerOptions(optionsMap);

        nabuCompiler.setListener(diagnostic -> {
            try {
                sendDiagnostic(diagnostic, out);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });
        nabuCompiler.setByteCodeGeneratorListener((sourceFile, classFile, className) -> {
            try {
                sendByteCodeMessage(sourceFile, classFile, className, out);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });

        if (options.hasOption(CompilerOption.SOURCE_PATH)) {
            final int result;

            try {
                result = nabuCompiler.compile(options);
            } catch (final RuntimeException | Error e) {
                // Tell the client what went wrong, instead of leaving it with a connection that just closes
                // (it would think the daemon is not there and compile again by itself).
                sendFailure(out, e);
                return;
            }

            sendEnd(out, result == 0);
        } else {
            sendEnd(out, true);
        }
    }

    private Map<String, String> readCompileOptions(final DataInputStream in) throws IOException {
        final var options = new HashMap<String, String>();

        final var count = in.readInt();
        for (var i = 0; i < count; i++) {
            // Length prefixed UTF-8: writeUTF/readUTF are limited to 64 KB, and a class path can be longer.
            final var length = in.readInt();
            final var bytes = new byte[length];
            in.readFully(bytes);
            final var option = new String(bytes, StandardCharsets.UTF_8);
            final var sep = option.indexOf(' ');
            final var key = option.substring(0, sep);
            final var value = option.substring(sep + 1);
            options.put(key, value);
        }

        return options;
    }

    private void sendCompileStarted(final DataOutputStream out) throws IOException {
        final var message = "=== COMPILATIE GESTART ===";
        byte[] data = (message + "\n").getBytes(StandardCharsets.UTF_8);
        out.writeByte(Protocol.STATUS_COMPILE_STARTED);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    /**
     * Converts the options a client sent ({@code option name -> value}) to compiler options. Every option is passed
     * on as it was sent, so a compilation by the daemon is the same as one in the process of the client.
     * Only the defaults of the first versions of the daemon are kept: a class output directory ({@code out}) and,
     * without a class path, the class output directory as class path.
     */
    static CompilerOptions toCompilerOptions(final Map<String, String> optionsMap) {
        final var builder = new CompilerOptions.CompilerOptionsBuilder();
        optionsMap.forEach((name, value) -> builder.option(new CompilerOption(name), value));

        final var outputDirectory = optionsMap.getOrDefault(CompilerOption.CLASS_OUTPUT.optionName(), "out");
        builder.option(CompilerOption.CLASS_OUTPUT, outputDirectory);

        if (!optionsMap.containsKey(CompilerOption.CLASS_PATH.optionName())) {
            builder.option(CompilerOption.CLASS_PATH, outputDirectory);
        }

        return builder.build();
    }

    private void sendFailure(final DataOutputStream out,
                             final Throwable failure) throws IOException {
        final var message = "Compilation by the daemon failed: " + failure;
        out.writeByte(Protocol.DIAGNOSTIC_ERROR);
        writeField(out, NO_DATA);
        writeField(out, toByteArray(message));
        out.writeInt(-1);
        out.writeInt(-1);
        out.flush();
        sendEnd(out, false);
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
        final var lineNumber = Objects.requireNonNullElse(diagnostic.getLineNumber(), -1);
        final var columnNumber = Objects.requireNonNullElse(diagnostic.getColumnNumber(), -1);

        out.writeByte(diagnosticCode);
        writeField(out, fileName);
        writeField(out, message);
        out.writeInt(lineNumber);
        out.writeInt(columnNumber);
        out.flush();
    }

    private void sendByteCodeMessage(final FileObject sourceFile,
                                     final PathFileObject classFile,
                                     final String className,
                                     final DataOutputStream out) throws IOException {
        final var sourceFileName = toByteArray(sourceFile.getFileName());
        final var classFileName = toByteArray(classFile.getFileName());

        out.writeByte(Protocol.BYTECODE_GENERATED);
        writeField(out, sourceFileName);
        writeField(out, classFileName);
        writeField(out, className.getBytes());

        out.flush();
    }

    private byte[] toByteArray(final CharSequence value) {
        return value != null ? toByteArray(value.toString()) : NO_DATA;
    }

    private byte[] toByteArray(final String value) {
        return value != null ? value.getBytes(StandardCharsets.UTF_8) : NO_DATA;
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

    private void writeField(final DataOutputStream out,
                            final byte[] message) throws IOException {
        out.writeInt(message.length);
        out.write(message);
    }
}

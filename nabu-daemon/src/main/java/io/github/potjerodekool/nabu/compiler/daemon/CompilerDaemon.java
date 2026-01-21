package io.github.potjerodekool.nabu.compiler.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.NabuCompiler;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executors;

public class CompilerDaemon {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(final String[] args) throws IOException {
        try (var serverSocket = new ServerSocket(8888);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            System.out.println("Starting daemon");
            final var address = serverSocket.getInetAddress();
            System.out.println("Daemon address " + address);


            while (true) {
                System.out.println("Waiting for client to connect");
                final var clientSocket = serverSocket.accept();
                System.out.println("Client connected");
                executor.submit(() -> handleClient(clientSocket));
            }
        }
    }

    private static void handleClient(final Socket clientSocket) {
        try (var in = clientSocket.getInputStream();
             var out = new PrintWriter(clientSocket.getOutputStream(), true)) {
            final var data = in.readAllBytes();
            System.out.println("data " + Arrays.toString(data));

            handleRequest(data, out);


        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void handleRequest(final byte[] data,
                                     final PrintWriter out) throws IOException {
        final var request = mapper.readValue(data, CompilerRequest.class);
        System.out.println("Request " + request);

        final var nabuCompiler = new NabuCompiler();
        final var compilerOptionsBuilder = new CompilerOptions.CompilerOptionsBuilder();

        configureClassPath(compilerOptionsBuilder, request);
        configureSourceRoots(compilerOptionsBuilder, request);

        compilerOptionsBuilder.option(
                CompilerOption.CLASS_OUTPUT,
                request.outputDirectory()
        );

        nabuCompiler.setListener(diagnostic -> {
            final var message = diagnostic.getMessage(Locale.getDefault());
            final var kind = diagnostic.getKind().name().toLowerCase();
            final var fileName = diagnostic.getFileObject().getFileName();

            final var diagnosticMessage = String.format("""
                    <%s message="%s", fileName="%s" />""", kind, message, fileName);

            out.println(diagnosticMessage);
            out.flush();
        });

        nabuCompiler.compile(compilerOptionsBuilder.build());
    }

    private static void configureSourceRoots(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder,
                                             final CompilerRequest request) {
        final var sourceRoots = request.sourcePath();
        final var sourcePath = String.join(File.pathSeparator, sourceRoots);
        compilerOptionsBuilder.option(CompilerOption.SOURCE_PATH, sourcePath);
    }

    private static void configureClassPath(final CompilerOptions.CompilerOptionsBuilder compilerOptionsBuilder,
                                           final CompilerRequest request) {
        final var paths = new ArrayList<String>();
        paths.add(request.outputDirectory());
        paths.addAll(request.classPath());
        compilerOptionsBuilder.option(
                CompilerOption.CLASS_PATH,
                String.join(File.pathSeparator, paths)
        );
    }
}

/*
    {
        "outputDirectory" : "build"
        "classPath": [
            "some.jar"
        ],
        "sourcePath": [
            "src/nabu"
        ]
    }
 */
package io.github.potjerodekool.nabu.compiler.backend.java;

import io.github.potjerodekool.nabu.compiler.backend.CompileException;
import io.github.potjerodekool.nabu.compiler.ir.IRBuilder;
import io.github.potjerodekool.nabu.compiler.ir.IRModule;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.lang.Flags;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaBackendTest {

    private final Path output = Paths.get("target/generated-code");

    @AfterEach
    void tearDown() throws IOException {

        try (var stream = Files.walk(output)) {
            stream.
                    forEach(path -> {
                        try {
                            if (!path.equals(output)) {
                                Files.deleteIfExists(path);
                            }

                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        Files.deleteIfExists(output);
    }

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(output);
    }

    @Test
    void compile() throws CompileException, IOException {
        final var javaBackend = new JavaBackend();
        final var module = new IRModule("HelloWorld");

        IRBuilder builder = new IRBuilder("HelloWorld");
        builder.beginFunction(
                "main",
                IRType.VOID,
                List.of(),
                Flags.PUBLIC | Flags.STATIC,
                false
        );
        builder.endFunction();

        javaBackend.compile(module, null, output);

        final var url = output.toUri().toURL();

        try (URLClassLoader loader = new URLClassLoader(new URL[]{url})) {
            loader.loadClass("HelloWorld");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

    }
}
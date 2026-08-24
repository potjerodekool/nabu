package io.github.potjerodekool.nabu.compiler.backend.java;

import io.github.potjerodekool.nabu.compiler.backend.CompileException;
import io.github.potjerodekool.nabu.compiler.ir.IRBuilder;
import io.github.potjerodekool.nabu.compiler.ir.IRModule;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import io.github.potjerodekool.nabu.compiler.lang.Flags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaBackendTest {

    @TempDir
    Path output;

    @BeforeEach
    void setUp() {
        assertDoesNotThrow(JavaBackend::requireJava24OrLater);
    }

    private IRModule moduleWithMain() {
        final var builder = new IRBuilder("HelloWorld");
        builder.beginFunction(
                "main",
                IRType.VOID,
                List.of(new IRValue.Temp("args", new IRType.Ptr(IRType.I8, "[Ljava/lang/String;"))),
                Flags.PUBLIC | Flags.STATIC,
                false
        );
        builder.endFunction();
        return builder.build();
    }

    @Test
    void compileWritesClassFileWithMainMethod() throws Exception {
        final var javaBackend = new JavaBackend();
        final var module = moduleWithMain();

        javaBackend.compile(module, null, output);

        final var classFile = output.resolve("HelloWorld.class");
        assertTrue(Files.exists(classFile), "Class file should be written");

        final var url = output.toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{url}, getClass().getClassLoader())) {
            final var clazz = loader.loadClass("HelloWorld");
            final var main = clazz.getMethod("main", String[].class);
            assertTrue(Modifier.isPublic(main.getModifiers()));
            assertTrue(Modifier.isStatic(main.getModifiers()));
        }
    }

    @Test
    void compileModuleWithoutMainGeneratesDefaultMain() throws Exception {
        final var builder = new IRBuilder("NoMain");
        builder.beginFunction("greet", IRType.VOID, List.of(), Flags.PUBLIC | Flags.STATIC, false);
        builder.endFunction();

        new JavaBackend().compile(builder.build(), null, output);

        final var url = output.toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{url}, getClass().getClassLoader())) {
            final var clazz = loader.loadClass("NoMain");
            assertDoesNotThrow(() -> clazz.getMethod("main", String[].class));
            assertDoesNotThrow(() -> clazz.getMethod("greet"));
        }
    }

    @Test
    void generatedMainIsExecutable() throws Exception {
        final var javaBackend = new JavaBackend();
        final var module = moduleWithMain();

        javaBackend.compile(module, null, output);

        final var url = output.toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{url}, getClass().getClassLoader())) {
            final var clazz = loader.loadClass("HelloWorld");
            clazz.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        }
    }
}

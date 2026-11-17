package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Method.getDefaultValue} of the elements of an annotation type from a library (bytecode), asked by library
 * code (Spring's AnnotationTypeMapping checks that aliased attributes declare default values).
 */
class NestedCatchE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void outerHandlerCatchesWhatInnerDoesNot() throws Exception {
        assertEquals("caught /nothing.properties not found\n0\n", compileAndRun(java.util.Map.of(
                "MyEx.java", """
                        package lib;
                        public class MyEx extends RuntimeException {
                            public MyEx(String m) { super(m); }
                        }
                        """,
                "Helper.java", """
                        package lib;
                        public class Helper {
                            public static java.io.InputStream open(String name) {
                                java.io.InputStream s = Helper.class.getResourceAsStream(name);
                                if (s == null) throw new MyEx(name + " not found");
                                return s;
                            }
                        }
                        """,
                "Cfg.java", """
                        package lib;
                        public class Cfg {
                            static final java.util.Properties P = new java.util.Properties();
                            static {
                                try {
                                    try {
                                        java.io.InputStream s = Helper.open("/nothing.properties");
                                        P.load(s);
                                    } catch (java.io.IOException e) {
                                        System.out.println("io");
                                    }
                                } catch (MyEx e) {
                                    System.out.println("caught " + e.getMessage());
                                }
                            }
                            public static int size() { return P.size(); }
                        }
                        """), "System.out.println(lib.Cfg.size());"));
    }

    /**
     * A static field read inside a try, then a call that throws (Spring's DisconnectedClientHelper): the check that the
     * class of the field is initialized splits the block, and the calls after it must still unwind to the handler.
     */
    @Test
    void callAfterStaticFieldReadInTryIsCaught() throws Exception {
        assertEquals("ignored no.such.Type\n1\n", compileAndRun(java.util.Map.of(
                "Registry.java", """
                        package lib;
                        public class Registry {
                            public static final java.util.Set<Class<?>> TYPES = new java.util.HashSet<>();
                            static { TYPES.add(String.class); }
                        }
                        """,
                "Helper.java", """
                        package lib;
                        public class Helper {
                            public static void add(String type) {
                                try {
                                    Registry.TYPES.add(Class.forName(type));
                                } catch (ClassNotFoundException ex) {
                                    System.out.println("ignored " + type);
                                }
                            }
                        }
                        """), "lib.Helper.add(\"no.such.Type\"); System.out.println(lib.Registry.TYPES.size());"));
    }

    /** Compiles the library sources with javac, then a Main with the given body natively against them; its output. */
    private String compileAndRun(final java.util.Map<String, String> libSources, final String mainBody) throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        final Path libSrc = tempDir.resolve("libsrc/lib");
        Files.createDirectories(libSrc);
        final java.util.List<String> javacArgs = new java.util.ArrayList<>();
        final Path libClasses = tempDir.resolve("libclasses");
        Files.createDirectories(libClasses);
        javacArgs.add("-d");
        javacArgs.add(libClasses.toString());
        for (final var source : libSources.entrySet()) {
            Files.writeString(libSrc.resolve(source.getKey()), source.getValue());
            javacArgs.add(libSrc.resolve(source.getKey()).toString());
        }
        final int javac = ToolProvider.getSystemJavaCompiler().run(null, null, null, javacArgs.toArray(String[]::new));
        assertEquals(0, javac);

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        %s
                    }
                }
                """.formatted(mainBody), StandardCharsets.UTF_8);
        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), """
                [ ]
                """);

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.CLASS_PATH, libClasses.toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.SYSTEM, new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();
        final var diagnostics = new java.util.ArrayList<String>();
        final var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        assertEquals(0, compiler.compile(options), String.join("\n", diagnostics));
        final Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(NativeToolchain::isExecutableOutput).findFirst().orElseThrow();
        }
        final String keep = System.getProperty("nabu.keepExe");
        if (keep != null) {
            Files.copy(exe, Path.of(keep), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (Stream<Path> walk = Files.walk(out)) {
                final Path ll = walk.filter(p -> p.getFileName().toString().endsWith(".ll"))
                        .max(java.util.Comparator.comparingLong(p -> p.toFile().length())).orElse(null);
                if (ll != null) {
                    Files.copy(ll, Path.of(keep.substring(0, keep.length() - ".exe".length()) + ".ll"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        final Path log = tempDir.resolve("run.log");
        final Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("programma hangt; output tot nu toe:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        final String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        final int exit = proc.exitValue();
        assertEquals(0, exit, output);
        return output;
    }
}

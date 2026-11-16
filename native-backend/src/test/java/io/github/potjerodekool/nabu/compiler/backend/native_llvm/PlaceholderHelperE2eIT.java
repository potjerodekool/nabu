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
class PlaceholderHelperE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void placeholderWithDefault() throws Exception {
        NativeToolchain.assumeGcc();
        final Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        final Path core = m2.resolve("org/springframework/spring-core/7.0.9/spring-core-7.0.9.jar");
        final Path jspecify = m2.resolve("org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(core) && Files.isRegularFile(jspecify),
                "spring-core 7.0.9 not in ~/.m2");
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        final Path libClasses = tempDir.resolve("libclasses");
        Files.createDirectories(libClasses);
        // A no-op commons-logging: spring-core logs through it, and the logging set-up is not what is tested here.
        final Path logSrc = tempDir.resolve("logsrc/org/apache/commons/logging");
        Files.createDirectories(logSrc);
        Files.writeString(logSrc.resolve("Log.java"), """
                package org.apache.commons.logging;
                public interface Log {
                    boolean isTraceEnabled(); boolean isDebugEnabled(); boolean isInfoEnabled();
                    boolean isWarnEnabled(); boolean isErrorEnabled(); boolean isFatalEnabled();
                    void trace(Object m); void trace(Object m, Throwable t); void debug(Object m); void debug(Object m, Throwable t);
                    void info(Object m); void info(Object m, Throwable t); void warn(Object m); void warn(Object m, Throwable t);
                    void error(Object m); void error(Object m, Throwable t); void fatal(Object m); void fatal(Object m, Throwable t);
                }
                """);
        Files.writeString(logSrc.resolve("LogFactory.java"), """
                package org.apache.commons.logging;
                public final class LogFactory {
                    private static final Log NOP = new Log() {
                        public boolean isTraceEnabled() { return false; } public boolean isDebugEnabled() { return false; }
                        public boolean isInfoEnabled() { return false; } public boolean isWarnEnabled() { return false; }
                        public boolean isErrorEnabled() { return false; } public boolean isFatalEnabled() { return false; }
                        public void trace(Object m) { } public void trace(Object m, Throwable t) { }
                        public void debug(Object m) { } public void debug(Object m, Throwable t) { }
                        public void info(Object m) { } public void info(Object m, Throwable t) { }
                        public void warn(Object m) { } public void warn(Object m, Throwable t) { }
                        public void error(Object m) { } public void error(Object m, Throwable t) { }
                        public void fatal(Object m) { } public void fatal(Object m, Throwable t) { }
                    };
                    public static Log getLog(Class<?> clazz) { return NOP; }
                    public static Log getLog(String name) { return NOP; }
                }
                """);
        final int javac = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", libClasses.toString(),
                logSrc.resolve("Log.java").toString(), logSrc.resolve("LogFactory.java").toString());
        assertEquals(0, javac);

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        org.springframework.util.PropertyPlaceholderHelper h =
                                new org.springframework.util.PropertyPlaceholderHelper("${", "}", ":", null, true);
                        System.out.println(h.replacePlaceholders("${springdoc.api-docs.path:/v3/api-docs}", name -> null));
                        System.out.println(h.replacePlaceholders("a${x}b${y:z}", name -> name.equals("x") ? "1" : null));
                        System.out.println(h.replacePlaceholders("plain", name -> null));
                    }
                }
                """, StandardCharsets.UTF_8);

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
                .option(CompilerOption.CLASS_PATH, libClasses + File.pathSeparator + core + File.pathSeparator + jspecify)
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
        assertEquals("/v3/api-docs\na1bz\nplain\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

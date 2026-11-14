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
 * Open world: a native program calls a class that was not part of the program at build time. The class is found on
 * NABU_CLASSPATH at run time and executed by the bytecode interpreter linked into the runtime (-Dnabu.interpreter=true).
 */
class OpenWorldInterpreterE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void nativeProgramRunsAClassItNeverSawAtBuildTime() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        // the plugin: compiled separately, NOT on the class path of the build
        final Path pluginSrc = tempDir.resolve("pluginsrc/plugin");
        Files.createDirectories(pluginSrc);
        Files.writeString(pluginSrc.resolve("Adder.java"), """
                package plugin;
                public class Adder {
                    static int twice(int x) { return x * 2; }
                    public static int combine(int a, int b) {
                        int s = 0;
                        for (int i = 0; i < b; i++) s += twice(a) + i;
                        return s;
                    }
                }
                """);
        final Path pluginClasses = tempDir.resolve("pluginclasses");
        Files.createDirectories(pluginClasses);
        final int javac = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17",
                "-d", pluginClasses.toString(), pluginSrc.resolve("Adder.java").toString());
        assertEquals(0, javac);
        final Path libClasses = tempDir.resolve("libclasses"); // empty: nothing from the plugin is known to the build
        Files.createDirectories(libClasses);

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.createDirectories(srcDir.resolve("org/nabu/interp"));
        Files.writeString(srcDir.resolve("org/nabu/interp/Interp.java"), """
                package org.nabu.interp;
                public class Interp {
                    public static native int callStaticInt(String className, String method, int a, int b);
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println(org.nabu.interp.Interp.callStaticInt("plugin.Adder", "combine", 5, 4));
                        System.out.println(Class.forName("plugin.Adder").getName());
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), """
                [ ]
                """);

        System.setProperty("nabu.interpreter", "true");
        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.CLASS_PATH, libClasses.toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.SYSTEM, System.getProperty("java.home"))
                .build();
        final var diagnostics = new java.util.ArrayList<String>();
        final var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        final int rc;
        try {
            rc = compiler.compile(options);
        } finally {
            System.clearProperty("nabu.interpreter");
        }
        assertEquals(0, rc, String.join("\n", diagnostics));
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
        final ProcessBuilder pb = NativeToolchain.run(exe.toAbsolutePath());
        pb.environment().put("NABU_CLASSPATH", pluginClasses.toString());
        final Process proc = pb.redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("programma hangt; output tot nu toe:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        final String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        final int exit = proc.exitValue();
        assertEquals("46\nplugin.Adder\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

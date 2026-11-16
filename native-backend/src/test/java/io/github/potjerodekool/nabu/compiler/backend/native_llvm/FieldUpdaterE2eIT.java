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
class FieldUpdaterE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void fieldUpdatersOfLibrary() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        final Path libSrc = tempDir.resolve("libsrc/lib");
        Files.createDirectories(libSrc);
        Files.writeString(libSrc.resolve("Holder.java"), """
                package lib;
                import java.util.concurrent.atomic.*;
                public class Holder {
                    private volatile String name = "a";
                    private volatile int count;
                    private volatile long total;
                    private static final AtomicReferenceFieldUpdater<Holder, String> NAME =
                            AtomicReferenceFieldUpdater.newUpdater(Holder.class, String.class, "name");
                    private static final AtomicIntegerFieldUpdater<Holder> COUNT =
                            AtomicIntegerFieldUpdater.newUpdater(Holder.class, "count");
                    private static final AtomicLongFieldUpdater<Holder> TOTAL =
                            AtomicLongFieldUpdater.newUpdater(Holder.class, "total");
                    public String run() {
                        StringBuilder sb = new StringBuilder();
                        sb.append(NAME.compareAndSet(this, "a", "b")).append(NAME.compareAndSet(this, "a", "c"))
                          .append(NAME.get(this)).append(NAME.getAndSet(this, "d")).append(name);
                        COUNT.set(this, 5);
                        sb.append(' ').append(COUNT.incrementAndGet(this)).append(COUNT.compareAndSet(this, 6, 9))
                          .append(COUNT.get(this)).append(count);
                        TOTAL.set(this, 1L << 40);
                        sb.append(' ').append(TOTAL.addAndGet(this, 2)).append(TOTAL.compareAndSet(this, 1L, 3L))
                          .append(total);
                        return sb.toString();
                    }
                }
                """);
        final Path libClasses = tempDir.resolve("libclasses");
        Files.createDirectories(libClasses);
        final int javac = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", libClasses.toString(),
                libSrc.resolve("Holder.java").toString());
        assertEquals(0, javac);

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println(new lib.Holder().run());
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
        assertEquals("truefalsebbd 6true99 1099511627778false1099511627778\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

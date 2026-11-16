package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The room allocated for an object is what the compiled code needs: longs after ints are aligned to 8 bytes. */
class ObjectSizeE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void anObjectWithLongsAfterIntsHasRoomForAllItsFields() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                public class Main {
                    interface Summer {
                        long sum();
                    }

                    static class Wide implements Summer {
                        int a1; long b1; int a2; long b2; int a3; long b3; int a4; long b4; int a5; long b5; int a6; long b6; int a7; long b7; int a8; long b8;

                        void fill(int seed) {
                            a1 = seed + 1; b1 = seed * 3L;
                            a2 = seed + 2; b2 = seed * 5L;
                            a3 = seed + 3; b3 = seed * 7L;
                            a4 = seed + 4; b4 = seed * 9L;
                            a5 = seed + 5; b5 = seed * 11L;
                            a6 = seed + 6; b6 = seed * 13L;
                            a7 = seed + 7; b7 = seed * 15L;
                            a8 = seed + 8; b8 = seed * 17L;
                        }

                        public long sum() {
                            return a1 + b1 + a2 + b2 + a3 + b3 + a4 + b4 + a5 + b5 + a6 + b6 + a7 + b7 + a8 + b8;
                        }
                    }

                    public static void main(String[] args) {
                        Wide[] objects = new Wide[2000];
                        Summer[] sums = new Summer[objects.length];
                        for (int i = 0; i < objects.length; i++) {
                            objects[i] = new Wide();
                            sums[i] = objects[i];
                        }
                        for (int i = 0; i < objects.length; i++) {
                            objects[i].fill(i);
                        }
                        long wrong = 0;
                        for (int i = 0; i < objects.length; i++) {
                            long expected = (i + 1) + 3L * i + (i + 2) + 5L * i + (i + 3) + 7L * i + (i + 4) + 9L * i + (i + 5) + 11L * i + (i + 6) + 13L * i + (i + 7) + 15L * i + (i + 8) + 17L * i;
                            if (sums[i].sum() != expected || !(sums[i] instanceof Wide)) {
                                wrong++;
                            }
                        }
                        System.out.println("wrong " + wrong);
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), "[ ]\n");

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.SYSTEM, System.getProperty("java.home"))
                .build();
        final var diagnostics = new java.util.ArrayList<String>();
        final var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        assertEquals(0, compiler.compile(options), String.join("\n", diagnostics));
        final Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(NativeToolchain::isExecutableOutput).findFirst().orElseThrow();
        }
        final Path log = tempDir.resolve("run.log");
        final Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("the program hangs");
        }
        final String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("wrong 0\n", output, "exit=" + proc.exitValue());
        assertEquals(0, proc.exitValue(), output);
    }
}

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

/**
 * What the release fence at the end of a constructor of a class with a final field costs: the same allocation loop,
 * compiled without (-Dnabu.ctorFence=0) and with it, each run three times, the times on the standard output. Not a
 * test of speed (nothing is asserted about it): it is run by hand on a machine of interest (arm64: the fence is a
 * {@code dmb ish}; x86: nothing).
 */
class CtorFenceBenchIT {

    @TempDir
    Path tempDir;

    @Test
    void allocationLoopWithAndWithoutTheFence() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        final StringBuilder report = new StringBuilder();
        for (final String mode : new String[]{"0", "1"}) {
            System.setProperty("nabu.ctorFence", mode);
            final Path srcDir = tempDir.resolve("src" + mode);
            Files.createDirectories(srcDir.resolve("app"));
            Files.writeString(srcDir.resolve("app/Main.java"), """
                    package app;

                    public class Main {
                        static final class P {
                            final int a;
                            final Object b;
                            P(int a, Object b) { this.a = a; this.b = b; }
                        }

                        public static void main(String[] args) {
                            final Object o = new Object();
                            final P[] keep = new P[1024];
                            for (int round = 0; round < 3; round++) {
                                final long t = System.nanoTime();
                                long s = 0;
                                for (int i = 0; i < 40_000_000; i++) {
                                    final P p = new P(i, o);
                                    keep[i & 1023] = p;
                                    s += p.a;
                                }
                                System.out.println("round " + round + " " + (System.nanoTime() - t) / 1_000_000 + " ms " + s);
                            }
                        }
                    }
                    """, StandardCharsets.UTF_8);
            final Path out = tempDir.resolve("out" + mode);
            Files.createDirectories(out.resolve("META-INF/native-image/test"));
            Files.writeString(out.resolve("META-INF/native-image/test/reflect-config.json"), "[ ]\n");
            System.setProperty("nabu.runtime",
                    new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
            final var options = new CompilerOptions.CompilerOptionsBuilder()
                    .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                    .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                    .option(CompilerOption.CLASS_OUTPUT, out.toString())
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
            final Path log = tempDir.resolve("run" + mode + ".log");
            final Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            if (!proc.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new AssertionError("hangs: " + Files.readString(log));
            }
            final String output = Files.readString(log).replace("\r\n", "\n");
            assertEquals(0, proc.exitValue(), output);
            report.append("ctorFence=").append(mode).append(" (os.arch=").append(System.getProperty("os.arch"))
                    .append(")\n").append(output);
        }
        System.out.println("CTOR-FENCE-BENCH\n" + report);
    }
}

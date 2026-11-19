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
/**
 * Weak and soft references are weak: the collector clears the referent when nothing else refers to it, and a reference with a
 * queue is put on its queue. Before, a reference was as strong as any other pointer, so the caches of a framework made of soft
 * references (Spring's ConcurrentReferenceHashMap) never shrank and a server ran out of memory. Every check works on twenty
 * objects and wants at least eighteen: a collector that looks at the stack conservatively may keep one of them alive.
 */
class WeakReferenceE2eIT {

    private static final String EXPECTED = String.join("\n",
            "weak cleared: true",
            "soft cleared: true",
            "held kept: true",
            "queued: true",
            "weak hash map shrinks: true",
            "clear: true",
            "refersTo: true",
            "phantom stays: true",
            "");

    @TempDir
    Path tempDir;

    @Test
    void weakAndSoftReferencesAreCleared() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        final Path libClasses = tempDir.resolve("libclasses");
        Files.createDirectories(libClasses);

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                import java.lang.ref.*;
                import java.util.*;

                public class Main {
                    static final int N = 20;

                    static void fillWeak(WeakReference<Object>[] refs) {
                        for (int i = 0; i < refs.length; i++) refs[i] = new WeakReference<>(new Object());
                    }

                    static void fillSoft(SoftReference<Object>[] refs) {
                        for (int i = 0; i < refs.length; i++) refs[i] = new SoftReference<>(new byte[100 + i]);
                    }

                    static void fillQueued(WeakReference<Object>[] refs, ReferenceQueue<Object> queue) {
                        for (int i = 0; i < refs.length; i++) refs[i] = new WeakReference<>(new Object(), queue);
                    }

                    static void fillMap(Map<Object, String> map) {
                        for (int i = 0; i < N; i++) map.put(new Object(), "value " + i);
                    }

                    static int cleared(Reference<?>[] refs) {
                        int n = 0;
                        for (Reference<?> r : refs) if (r.get() == null) n++;
                        return n;
                    }

                    /** Soft references are kept except in every 8th collection (NABU_SOFT_REF_EVERY): nine collections include one. */
                    static void collect() {
                        for (int i = 0; i < 9; i++) Runtime.getRuntime().gc();
                    }

                    @SuppressWarnings("unchecked")
                    public static void main(String[] args) throws Exception {
                        WeakReference<Object>[] weak = new WeakReference[N];
                        SoftReference<Object>[] soft = new SoftReference[N];
                        WeakReference<Object>[] queued = new WeakReference[N];
                        ReferenceQueue<Object> queue = new ReferenceQueue<>();
                        Map<Object, String> map = new WeakHashMap<>();
                        fillWeak(weak);
                        fillSoft(soft);
                        fillQueued(queued, queue);
                        fillMap(map);
                        Object held = new Object();
                        WeakReference<Object> heldRef = new WeakReference<>(held);
                        WeakReference<Object> cleared = new WeakReference<>(held);
                        cleared.clear();
                        PhantomReference<Object> phantom = new PhantomReference<>(held, new ReferenceQueue<>());

                        collect();

                        System.out.println("weak cleared: " + (cleared(weak) >= N - 2));
                        System.out.println("soft cleared: " + (cleared(soft) >= N - 2));
                        System.out.println("held kept: " + (heldRef.get() == held));
                        int polled = 0;
                        while (queue.poll() != null) polled++;
                        System.out.println("queued: " + (polled >= N - 2));
                        System.out.println("weak hash map shrinks: " + (map.size() <= 2));
                        System.out.println("clear: " + (cleared.get() == null && heldRef.get() == held));
                        System.out.println("refersTo: " + (heldRef.refersTo(held) && !heldRef.refersTo(null)));
                        System.out.println("phantom stays: " + (phantom.refersTo(held) && held != null));
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
        assertEquals(EXPECTED, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Threads in a native executable: they are real native threads that run one at a time and switch where
 * they block. The programs are in {@code src/test/resources/threads/<test name>.java}, the output the JVM
 * prints in {@code <test name>.expected}.
 */
class ThreadsE2eIT {

    @Test
    void threadsStartJoinWaitAndInterrupt() throws Exception {
        assertEquals(expected("threadsStartJoinWaitAndInterrupt"), output("threadsStartJoinWaitAndInterrupt"));
    }

    @Test
    void threadSubclass() throws Exception {
        assertEquals(expected("threadSubclass"), output("threadSubclass"));
    }

    @Test
    void completableFutureRunsAsync() throws Exception {
        assertEquals(expected("completableFutureRunsAsync"), output("completableFutureRunsAsync"));
    }

    @Test
    void forkJoinPoolAndParallelStream() throws Exception {
        assertEquals(expected("forkJoinPoolAndParallelStream"), output("forkJoinPoolAndParallelStream"));
    }

    @Test
    void timerRunsTask() throws Exception {
        assertEquals(expected("timerRunsTask"), output("timerRunsTask"));
    }

    @Test
    void spinningThreadsLetOthersRun() throws Exception {
        assertEquals(expected("spinningThreadsLetOthersRun"), output("spinningThreadsLetOthersRun"));
    }

    @Test
    void executorRunsTasks() throws Exception {
        assertEquals(expected("executorRunsTasks"), output("executorRunsTasks"));
    }

    @Test
    void uncaughtExceptionHandlers() throws Exception {
        assertEquals(expected("uncaughtExceptionHandlers"), output("uncaughtExceptionHandlers"));
    }

    @Test
    void threadGroupTree() throws Exception {
        assertEquals(expected("threadGroupTree"), output("threadGroupTree"));
    }

    @Test
    void threadGroupList() throws Exception {
        assertEquals(expected("threadGroupList"), output("threadGroupList").replaceAll("(?m)^(\\s+)Thread\\[.*$", "$1listed"));
    }

    @Test
    void structuredTaskScope() throws Exception {
        assertEquals(expected("structuredTaskScope"), output("structuredTaskScope"));
    }

    @Test
    void structuredTaskScopeFailureAndScopedValue() throws Exception {
        assertEquals(expected("structuredTaskScopeFailureAndScopedValue"), output("structuredTaskScopeFailureAndScopedValue"));
    }

    @Test
    void structuredTaskScopeAnySuccessful() throws Exception {
        assertEquals(expected("structuredTaskScopeAnySuccessful"), output("structuredTaskScopeAnySuccessful"));
    }

    @Test
    void structuredTaskScopeAllSuccessful() throws Exception {
        assertEquals(expected("structuredTaskScopeAllSuccessful"), output("structuredTaskScopeAllSuccessful"));
    }

    @Test
    void structuredTaskScopeAwaitAllAndShutdown() throws Exception {
        assertEquals(expected("structuredTaskScopeAwaitAllAndShutdown"), output("structuredTaskScopeAwaitAllAndShutdown"));
    }

    @Test
    void structuredTaskScopeTimeout() throws Exception {
        assertEquals(expected("structuredTaskScopeTimeout"), output("structuredTaskScopeTimeout"));
    }

    /** UnaryOperator declares no method: its itable slots come from Function, which has to count as implemented. */
    @Test
    void anonymousClassOfInterfaceWithoutOwnMethods() throws Exception {
        assertEquals(expected("anonymousClassOfInterfaceWithoutOwnMethods"), output("anonymousClassOfInterfaceWithoutOwnMethods"));
    }

    @Test
    void locksAndSynchronizers() throws Exception {
        assertEquals(expected("locksAndSynchronizers"), output("locksAndSynchronizers"));
    }

    @Test
    void blockingQueues() throws Exception {
        assertEquals(expected("blockingQueues"), output("blockingQueues"));
    }

    @Test
    void scheduledExecutorAndCompletableFuture() throws Exception {
        assertEquals(expected("scheduledExecutorAndCompletableFuture"), output("scheduledExecutorAndCompletableFuture"));
    }

    @Test
    void lambdasWithThreadsAndFutures() throws Exception {
        assertEquals(expected("lambdasWithThreadsAndFutures"), output("lambdasWithThreadsAndFutures"));
    }

    @Test
    void phaserExchangerAndDelayQueue() throws Exception {
        assertEquals(expected("phaserExchangerAndDelayQueue"), output("phaserExchangerAndDelayQueue"));
    }

    @Test
    void atomicArrays() throws Exception {
        assertEquals(expected("atomicArrays"), output("atomicArrays"));
    }

    @Test
    void moreSynchronizers() throws Exception {
        assertEquals(expected("moreSynchronizers"), output("moreSynchronizers"));
    }

    @Test
    void rethrowFromHandlerThroughNonMatchingCatch() throws Exception {
        assertEquals(expected("rethrowFromHandlerThroughNonMatchingCatch"), output("rethrowFromHandlerThroughNonMatchingCatch"));
    }

    @Test
    void virtualThreads() throws Exception {
        assertEquals(expected("virtualThreads"), output("virtualThreads"));
    }

    @Test
    void virtualThreadExecutor() throws Exception {
        assertEquals(expected("virtualThreadExecutor"), output("virtualThreadExecutor"));
    }

    @Test
    void scopedValues() throws Exception {
        assertEquals(expected("scopedValues"), output("scopedValues"));
    }

    @Test
    void threadGroupInterruptAndPriority() throws Exception {
        assertEquals(expected("threadGroupInterruptAndPriority"), output("threadGroupInterruptAndPriority"));
    }

    @Test
    void parallelCounters() throws Exception {
        assertEquals(expected("parallelCounters"), output("parallelCounters"));
    }

    /** NABU_GIL=1 runs the threads one at a time again; the counters must come out the same. */
    @Test
    void parallelCountersWithTheGlobalLock() throws Exception {
        assertEquals(expected("parallelCounters"), output("parallelCounters", java.util.Map.of("NABU_GIL", "1")));
    }

    @Test
    void pingPong() throws Exception {
        assertEquals(expected("pingPong"), output("pingPong"));
    }

    @Test
    void manyVirtualThreads() throws Exception {
        assertEquals(expected("manyVirtualThreads"), output("manyVirtualThreads"));
    }

    @Test
    void processWaitFor() throws Exception {
        assertEquals(expected("processWaitFor"), output("processWaitFor"));
    }

    @Test
    void threadStackTraces() throws Exception {
        assertEquals(expected("threadStackTraces"), output("threadStackTraces"));
    }

    @Test
    void sharedRuntimeObjects() throws Exception {
        assertEquals(expected("sharedRuntimeObjects"), output("sharedRuntimeObjects"));
    }

    @Test
    void parallelSpeed() throws Exception {
        assertEquals(expected("parallelSpeed"), output("parallelSpeed"));
    }

    @Test
    void objectAcrossInvokeAll() throws Exception {
        assertEquals(expected("objectAcrossInvokeAll"), output("objectAcrossInvokeAll"));
    }

    @Test
    void completionServiceAndLinkedQueue() throws Exception {
        assertEquals(expected("completionServiceAndLinkedQueue"), output("completionServiceAndLinkedQueue"));
    }

    @Test
    void threadStatesInheritableLocalsAndExecutorExtras() throws Exception {
        assertEquals(expected("threadStatesInheritableLocalsAndExecutorExtras"), output("threadStatesInheritableLocalsAndExecutorExtras"));
    }

    /** The expected output of a program: what the JVM prints. */
    private static String expected(final String name) throws IOException {
        return resource(name + ".expected");
    }

    private static String resource(final String file) throws IOException {
        try (var in = ThreadsE2eIT.class.getResourceAsStream("/threads/" + file)) {
            if (in == null) {
                throw new IOException("no resource /threads/" + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The names of the programs: one per test, in /threads/<name>.java. */
    private static List<String> programNames() {
        final String only = System.getProperty("nabu.threads.only");
        final List<String> names = new ArrayList<>();
        for (final Method method : ThreadsE2eIT.class.getDeclaredMethods()) {
            // a test that runs another program in another way has no program of its own
            if (method.isAnnotationPresent(Test.class)
                    && ThreadsE2eIT.class.getResource("/threads/" + method.getName() + ".java") != null) {
                names.add(method.getName());
            }
        }
        Collections.sort(names);
        if (only != null && !only.isBlank()) {
            names.retainAll(List.of(only.split(",")));
        }
        return names;
    }

    private static Path buildDir;
    private static Path executable;

    @AfterAll
    static void deleteBuild() throws IOException {
        if (buildDir != null) {
            try (Stream<Path> walk = Files.walk(buildDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * Every program is compiled once, together, into one executable: building the JDK part of an executable takes
     * about half a minute, so a build per program would take most of the time of this class. A program lives in a
     * package of its own (its {@code main} is renamed {@code run}) and {@code Main} starts the one that is named
     * by the first argument. Set {@code -Dnabu.threads.only=name,name} to build only some of them.
     */
    private static synchronized Path executable() throws Exception {
        if (executable != null) {
            return executable;
        }
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        buildDir = Files.createTempDirectory("threads-it");
        final Path srcDir = buildDir.resolve("src");
        Files.createDirectories(srcDir);
        final StringBuilder dispatch = new StringBuilder("public class Main {\n"
                + "    public static void main(String[] args) throws Exception {\n");
        final List<String> names = programNames();
        for (int i = 0; i < names.size(); i++) {
            final String pkg = "t" + i;
            final String source = resource(names.get(i) + ".java").replace("static void main(", "static void run(");
            Files.createDirectories(srcDir.resolve(pkg));
            Files.writeString(srcDir.resolve(pkg).resolve("Main.java"), "package " + pkg + ";\n\n" + source,
                    StandardCharsets.UTF_8);
            dispatch.append("        if (args[0].equals(\"").append(names.get(i)).append("\")) {\n")
                    .append("            ").append(pkg).append(".Main.run(new String[0]);\n")
                    .append("            return;\n        }\n");
        }
        dispatch.append("    }\n}\n");
        Files.writeString(srcDir.resolve("Main.java"), dispatch.toString(), StandardCharsets.UTF_8);

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final Path out = buildDir.resolve("out");
        Files.createDirectories(out);
        var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.SYSTEM, new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();
        var diagnostics = new ArrayList<String>();
        var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        assertEquals(0, compiler.compile(options), String.join("\n", diagnostics));
        try (Stream<Path> walk = Files.walk(out)) {
            executable = walk.filter(NativeToolchain::isExecutableOutput).findFirst().orElseThrow();
        }
        final String keep = System.getProperty("nabu.keepExe");
        if (keep != null) {
            Files.copy(executable, Path.of(keep), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return executable;
    }

    /** Runs one program of the executable and returns what it printed; the exit code must be 0. */
    private String output(final String name) throws Exception {
        return output(name, java.util.Map.of());
    }

    /** Same, with extra environment variables for the program. */
    private String output(final String name, final java.util.Map<String, String> environment) throws Exception {
        final Path exe = executable();
        final Path log = Files.createTempFile(buildDir, name, ".log");
        final ProcessBuilder builder = NativeToolchain.run(exe.toAbsolutePath());
        builder.environment().putAll(environment);
        builder.command().add(name);
        final Process proc = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("the program hangs; output so far:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        final String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(0, proc.exitValue(), output);
        return output;
    }
}

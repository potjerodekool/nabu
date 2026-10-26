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
 * Kopieconstructors van JDK-collecties uit bytecode: picocli's
 * {@code CommandSpec.addMixin} kopieert {@code mixin.options()} (een
 * unmodifiableList) naar een {@code LinkedHashSet} en itereert daarover; native
 * bleef die set leeg, waardoor --help/--version nooit als optie werden toegevoegd
 * (hasNext() staat in de abstracte LinkedHashIterator, die Iterator niet zelf
 * implementeert). Daarnaast: een object-operand in een string-concat werd "".
 */
class CollectionCopyE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void linkedHashSetCopyOfUnmodifiableList() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                import java.util.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        List<String> list = new ArrayList<String>();
                        list.add("a"); list.add("b");
                        List<String> ro = Collections.unmodifiableList(list);
                        System.out.println(ro.size());
                        Set<String> s1 = new LinkedHashSet<String>(list);
                        System.out.println(s1.size() + " " + s1);
                        Set<String> s2 = new LinkedHashSet<String>(ro);
                        System.out.println(s2.size() + " " + s2);
                        Set<String> s3 = new HashSet<String>(ro);
                        System.out.println(s3.size());
                        Set<String> s4 = new LinkedHashSet<String>();
                        s4.addAll(ro);
                        System.out.println(s4.size());
                        for (String x : s2) { System.out.println("it " + x); }
                        Object none = null;
                        System.out.println("x" + none + Integer.valueOf(5));
                    }
                }
                """, StandardCharsets.UTF_8);
        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.SYSTEM, new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();
        var diagnostics = new java.util.ArrayList<String>();
        var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        assertEquals(0, compiler.compile(options), String.join("\n", diagnostics));
        Path exe;
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
                    Files.copy(ll, Path.of(keep.substring(0, keep.length() - ".exe".length()) + ".ll"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        final Path log = tempDir.resolve("run.log");
        Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("programma hangt; output tot nu toe:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int exit = proc.exitValue();
        assertEquals("2\n2 [a, b]\n2 [a, b]\n2\n2\nit a\nit b\nxnull5\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

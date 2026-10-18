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
 * String.format met {@code %d} loopt via LocaleProviderAdapter.forType, dat
 * zijn adapter reflectief instantieert
 * ({@code Class.forName(..).getDeclaredConstructor().newInstance()}).
 * Constructor.newInstance gaf null en format faalde met een NPE; nu bouwt de
 * runtime het object via {@code nabu_ctor_table}. Zelfde mechanisme voor een
 * source-klasse (picocli's DefaultFactory). Met dezelfde opties als de
 * picocli-demo (BOEHM, Pattern/Matcher-sluiting).
 */
class FormatReflectionE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void formatAndReflectiveInstantiation() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Made.java"), """
                public class Made {
                    public String hello() { return "made"; }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(srcDir.resolve("Main.java"), """
                import java.text.BreakIterator;
                import java.util.Formatter;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println(new StringBuilder().append("sb-").append(2).append('x').toString());
                        System.out.println(Integer.toString(37));
                        System.out.println(new Formatter().format("%s|", "ab").toString());
                        System.out.println(String.format("fmt %s %d", "ab", 3));
                        BreakIterator lines = BreakIterator.getLineInstance();
                        lines.setText("ab cd");
                        System.out.println("break " + lines.next());
                        System.out.printf("printf %s %d%n", "cd", 42);
                        Object made = Class.forName("Made").getDeclaredConstructor().newInstance();
                        System.out.println(((Made) made).hello());
                        try {
                            Long.decode("--version");
                            System.out.println("decode: no exception");
                        } catch (NumberFormatException e) {
                            System.out.println("decode: NFE");
                        }
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
            exe = walk.filter(p -> p.getFileName().toString().endsWith(".exe")).findFirst().orElseThrow();
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
        assertEquals("sb-2x\n37\nab|\nfmt ab 3\nbreak 3\nprintf cd 42\nmade\ndecode: NFE\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

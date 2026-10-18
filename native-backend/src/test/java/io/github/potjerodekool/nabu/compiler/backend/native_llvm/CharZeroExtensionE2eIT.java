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
 * char is in de IR net als short een i16. Een char >= 0x8000 werd bij verbreding
 * naar int teken-uitgebreid ({@code (int) '\uffff'} gaf -1). JLS 5.1.2: char
 * verbreedt zonder teken, short met; dat moet zowel voor broncode als voor
 * uit JDK-bytecode gecompileerde methodes (String.hashCode/codePointAt) kloppen.
 */
class CharZeroExtensionE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void charWidensWithoutSign() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                public class Main {
                    static char f = '\\uffff';
                    static int id(char c) { return c; }
                    public static void main(String[] args) throws Exception {
                        char c = '\\uffff';
                        int i = c;
                        System.out.println(i);
                        System.out.println((int) c);
                        System.out.println(c + 1);
                        char[] a = new char[] {'\\u8000'};
                        System.out.println(a[0] + 0);
                        System.out.println(id('\\u9000'));
                        System.out.println(f + 0);
                        long l = c;
                        System.out.println(l);
                        short sh = -1;
                        int j = sh;
                        System.out.println(j);
                        String s = "\\uffff\\u8001";
                        System.out.println((int) s.charAt(0));
                        System.out.println(s.hashCode());
                        System.out.println(s.codePointAt(1));
                        System.out.println(c > 0x7fff);
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
        assertEquals("65535\n65535\n65536\n32768\n36864\n65535\n65535\n-1\n65535\n2064354\n32769\ntrue\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

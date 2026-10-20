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
 * java.io.File compiled from the JDK's bytecode, with the natives of
 * WinNTFileSystem/UnixFileSystem and FileInputStream/FileOutputStream.open0
 * in the C runtime: create a directory, write, read back, rename, list and
 * delete. File used to be provided by the runtime, and its constructor had
 * no implementation (exit code 127).
 */
class FileSystemE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void filesAndDirectories() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                public class Main {
                    public static void main(String[] args) throws Exception {
                        java.io.File dir = new java.io.File("work");
                        System.out.println(dir.exists() + " " + dir.mkdir() + " " + dir.isDirectory());
                        java.io.File f = new java.io.File(dir, "a.txt");
                        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                        out.write("hello file".getBytes());
                        out.close();
                        System.out.println(f.exists() + " " + f.isFile() + " " + f.length());
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(f));
                        System.out.println(reader.readLine());
                        reader.close();
                        java.io.File c = new java.io.File(dir, "Case.txt");
                        new java.io.FileOutputStream(c).close();
                        String canonical = new java.io.File("work/../work/CASE.TXT").getCanonicalPath();
                        System.out.println(canonical.endsWith(java.io.File.separator + "work" + java.io.File.separator
                                + (java.io.File.separatorChar == '\\\\' ? "Case.txt" : "CASE.TXT")) + " " + c.delete());
                        // More unclosed streams than a process can have descriptors: the garbage
                        // collector has to close them. The last ones can still be referenced.
                        java.io.File leak = new java.io.File("leak.txt");
                        new java.io.FileOutputStream(leak).close();
                        int opened = 0;
                        for (int i = 0; i < 1500; i++) {
                            new java.io.FileInputStream(leak);
                            opened++;
                            if (i % 100 == 99) System.gc();
                        }
                        System.out.println("opened " + opened);
                        leak.delete();
                        java.io.File g = new java.io.File(dir, "b.txt");
                        System.out.println(f.renameTo(g) + " " + f.exists() + " " + java.util.Arrays.toString(dir.list()));
                        System.out.println(g.getName() + " " + g.getAbsolutePath().endsWith("b.txt") + " " + g.delete()
                                + " " + dir.delete() + " " + dir.exists());
                        try {
                            new java.io.FileInputStream(new java.io.File(dir, "missing"));
                        } catch (java.io.FileNotFoundException e) {
                            System.out.println("fnf " + (e.getMessage() != null));
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
        Process proc = NativeToolchain.run(exe.toAbsolutePath()).directory(tempDir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("programma hangt; output tot nu toe:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int exit = proc.exitValue();
        assertEquals("false true true\ntrue true 10\nhello file\ntrue true\nopened 1500\ntrue false [b.txt]\nb.txt true true true false\n"
                + "fnf true\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

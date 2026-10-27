package io.github.potjerodekool.nabu.nativeimage;

import io.github.potjerodekool.nabu.compiler.backend.native_llvm.Linker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command on a program that is only a jar: no source of ours, built by javac. */
class NativeImageCommandIT {

    @TempDir
    Path temp;

    @Test
    void aJarWithAMainClassBecomesAnExecutable() throws Exception {
        final String gcc = Linker.resolveCompiler("gcc");
        Assumptions.assumeTrue(!gcc.equals("gcc") && Files.isExecutable(Path.of(gcc)),
                "no gcc: set -Dnabu.gcc=<path> or -Dnabu.toolchain.bin=<dir>");
        final var javac = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(javac != null, "needs a JDK");

        final Path src = Files.createDirectories(temp.resolve("src/app"));
        Files.writeString(src.resolve("Main.java"), """
                package app;

                import java.util.List;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println("hello " + List.of("a", "b") + " args=" + args.length);
                    }
                }
                """);
        final Path classes = Files.createDirectories(temp.resolve("classes"));
        assertEquals(0, javac.run(null, null, null, "-d", classes.toString(), src.resolve("Main.java").toString()));

        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "app.Main");
        final Path jar = temp.resolve("hello.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("app/Main.class"));
            out.write(Files.readAllBytes(classes.resolve("app/Main.class")));
            out.closeEntry();
        }

        final Path outDir = temp.resolve("bin");
        final var out = new ByteArrayOutputStream();
        final var err = new ByteArrayOutputStream();
        final int exit = NativeImage.run(List.of("-d", outDir.toString(), "-jar", jar.toString()),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));

        final Path exe = outDir.resolve("hello" + NativeImage.executableSuffix());
        assertTrue(Files.isRegularFile(exe), "no executable " + exe + "\n" + out.toString(StandardCharsets.UTF_8));
        assertTrue(!Files.exists(outDir.resolve(".nabu-build-hello")), "the work directory is removed");

        final var pb = new ProcessBuilder(exe.toString(), "x").redirectErrorStream(true);
        final Path bin = Path.of(gcc).toAbsolutePath().getParent();
        if (bin != null) {
            pb.environment().keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst()
                    .ifPresent(k -> pb.environment().compute(k, (key, v) -> bin + File.pathSeparator + v));
        }
        final Process process = pb.start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals("hello [a, b] args=1\n", output);
    }

    @Test
    void aMissingMainClassIsReportedBeforeTheBuild() throws Exception {
        final var err = new ByteArrayOutputStream();
        final int exit = NativeImage.run(List.of("-cp", temp.toString(), "no.Such"),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("no.Such not found"));
    }
}

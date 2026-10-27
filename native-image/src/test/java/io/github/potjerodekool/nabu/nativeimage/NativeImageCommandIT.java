package io.github.potjerodekool.nabu.nativeimage;

import io.github.potjerodekool.nabu.compiler.backend.native_llvm.Linker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command on programs that are only a jar: no source of ours, built by javac. */
class NativeImageCommandIT {

    @TempDir
    Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void aJarWithAMainClassBecomesAnExecutable() throws Exception {
        final Path jar = jar("app.Main", Map.of("app/Main.java", """
                package app;

                import java.util.List;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println("hello " + List.of("a", "b") + " args=" + args.length);
                    }
                }
                """), Map.of());

        final Path exe = build(0, "-d", temp.resolve("bin").toString(), "-jar", jar.toString());

        assertEquals("hello [a, b] args=1\n", run(exe, "x"));
        assertTrue(!Files.exists(temp.resolve("bin/.nabu-build-hello")), "the work directory is removed");
    }

    @Test
    void serviceLoaderFindsTheProvidersOfTheServicesFiles() throws Exception {
        final Path jar = jar("app.Main", Map.of(
                "app/Greeter.java", """
                        package app;

                        public interface Greeter {
                            String greet(String name);
                        }
                        """,
                "app/Polite.java", """
                        package app;

                        public class Polite implements Greeter {
                            public String greet(String name) {
                                return "Good day, " + name;
                            }
                        }
                        """,
                "app/Casual.java", """
                        package app;

                        public class Casual implements Greeter {
                            public String greet(String name) {
                                return "Hi " + name;
                            }
                        }
                        """,
                "app/Main.java", """
                        package app;

                        import java.util.ServiceLoader;

                        public class Main {
                            public static void main(String[] args) {
                                int count = 0;
                                for (Greeter greeter : ServiceLoader.load(Greeter.class)) {
                                    System.out.println(greeter.greet("Nabu"));
                                    count++;
                                }
                                System.out.println(count + " providers");
                            }
                        }
                        """),
                Map.of("META-INF/services/app.Greeter", "# the greeters\napp.Polite\napp.Casual # informal\n"));

        final Path exe = build(0, "-d", temp.resolve("bin").toString(), "-jar", jar.toString());

        assertEquals("Good day, Nabu\nHi Nabu\n2 providers\n", run(exe));
    }

    @Test
    void strictFailsOnANativeMethodOfTheProgram() throws Exception {
        final Path jar = jar("app.Main", Map.of("app/Main.java", """
                package app;

                public class Main {
                    static native int answer();

                    public static void main(String[] args) {
                        System.out.println(args.length > 5 ? answer() : 0);
                    }
                }
                """), Map.of());

        build(1, "--strict", "-d", temp.resolve("bin").toString(), "-jar", jar.toString());

        assertTrue(err.toString(StandardCharsets.UTF_8).contains("app.Main_answer"), err.toString(StandardCharsets.UTF_8));
        assertTrue(!Files.exists(temp.resolve("bin/hello" + NativeImage.executableSuffix())));
    }

    @Test
    void aMissingMainClassIsReportedBeforeTheBuild() {
        final int exit = NativeImage.run(List.of("-cp", temp.toString(), "no.Such"),
                new PrintStream(out), new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("no.Such not found"));
    }

    private Path build(final int expectedExit, final String... args) {
        final int exit = NativeImage.run(List.of(args),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(expectedExit, exit, err.toString(StandardCharsets.UTF_8));
        return temp.resolve("bin").resolve("hello" + NativeImage.executableSuffix());
    }

    /** Compiles the sources with javac and puts the classes and the resources in {@code hello.jar}. */
    private Path jar(final String mainClass, final Map<String, String> sources, final Map<String, String> resources)
            throws IOException {
        final String gcc = Linker.resolveCompiler("gcc");
        Assumptions.assumeTrue(!gcc.equals("gcc") && Files.isExecutable(Path.of(gcc)),
                "no gcc: set -Dnabu.gcc=<path> or -Dnabu.toolchain.bin=<dir>");
        final var javac = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(javac != null, "needs a JDK");

        final Path src = temp.resolve("src");
        final Path classes = Files.createDirectories(temp.resolve("classes"));
        final List<String> javacArgs = new ArrayList<>(List.of("-d", classes.toString()));
        for (final var source : new LinkedHashMap<>(sources).entrySet()) {
            final Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            javacArgs.add(file.toString());
        }
        assertEquals(0, javac.run(null, null, null, javacArgs.toArray(String[]::new)));

        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        final Path jar = temp.resolve("hello.jar");
        try (var jarOut = new JarOutputStream(Files.newOutputStream(jar), manifest);
             var walk = Files.walk(classes)) {
            for (final Path file : walk.filter(Files::isRegularFile).toList()) {
                jarOut.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                jarOut.write(Files.readAllBytes(file));
                jarOut.closeEntry();
            }
            for (final var resource : resources.entrySet()) {
                jarOut.putNextEntry(new JarEntry(resource.getKey()));
                jarOut.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                jarOut.closeEntry();
            }
        }
        return jar;
    }

    private String run(final Path exe, final String... args) throws Exception {
        assertTrue(Files.isRegularFile(exe), "no executable " + exe + "\n" + out.toString(StandardCharsets.UTF_8));
        final List<String> command = new ArrayList<>(List.of(exe.toString()));
        command.addAll(List.of(args));
        final var pb = new ProcessBuilder(command).redirectErrorStream(true);
        final Path bin = Path.of(Linker.resolveCompiler("gcc")).toAbsolutePath().getParent();
        if (bin != null) {
            pb.environment().keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst()
                    .ifPresent(k -> pb.environment().compute(k, (key, v) -> bin + File.pathSeparator + v));
        }
        final Process process = pb.start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        return output;
    }
}

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
 * Application resources natively: the files a {@code resource-config.json}
 * selects (includes minus excludes, plus the properties of a bundle) are
 * embedded, and {@code Class.getResource[AsStream]} (relative and absolute
 * names), {@code ClassLoader.getSystemResourceAsStream} and a
 * {@code resource:} URL read them. A file outside the configuration is not
 * there, as with GraalVM {@code native-image}. {@code ResourceBundle.getBundle}
 * finds the properties of a configured bundle and its class-based variants
 * (here one from source), with the JDK's candidate and fallback rules.
 */
class ResourcesE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void configuredResourcesAreEmbedded() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("greet"));
        Files.writeString(srcDir.resolve("greet/Labels_de.java"), """
                package greet;

                public class Labels_de extends java.util.ListResourceBundle {
                    protected Object[][] getContents() {
                        return new Object[][] { { "label", "Hallo" } };
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(srcDir.resolve("greet/Main.java"), """
                package greet;

                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;

                public class Main {
                    static String read(InputStream in) throws Exception {
                        if (in == null) {
                            return "null";
                        }
                        try (in) {
                            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                        }
                    }
                    public static void main(String[] args) throws Exception {
                        System.out.println(read(Main.class.getResourceAsStream("messages.txt")));
                        java.util.Properties properties = new java.util.Properties();
                        properties.load(Main.class.getResourceAsStream("/data/info.properties"));
                        System.out.println(properties.getProperty("name"));
                        System.out.println(read(ClassLoader.getSystemResourceAsStream("data/skip.txt"))
                                + " " + read(Main.class.getResourceAsStream("/other.txt")));
                        java.net.URL url = Main.class.getResource("messages.txt");
                        System.out.println(url.getProtocol() + " " + url.getPath() + " " + read(url.openStream()));
                        System.out.println(read(ClassLoader.getSystemResourceAsStream("greet/Labels_nl.properties")));
                        StringBuilder all = new StringBuilder();
                        java.util.Enumeration<java.net.URL> urls = ClassLoader.getSystemResources("greet/messages.txt");
                        while (urls.hasMoreElements()) {
                            java.net.URL u = urls.nextElement();
                            all.append(u).append('=').append(read(u.openStream())).append(';');
                        }
                        System.out.println(all);
                        java.util.Locale.setDefault(java.util.Locale.ROOT);
                        java.util.ResourceBundle nl = java.util.ResourceBundle.getBundle("greet.Labels", java.util.Locale.forLanguageTag("nl"));
                        java.util.ResourceBundle de = java.util.ResourceBundle.getBundle("greet.Labels", java.util.Locale.GERMAN);
                        java.util.ResourceBundle fr = java.util.ResourceBundle.getBundle("greet.Labels", java.util.Locale.FRENCH);
                        System.out.println(nl.getString("label") + " " + nl.getString("bye") + " " + nl.getLocale()
                                + " | " + de.getString("label") + " " + de.getString("bye")
                                + " | " + fr.getString("label") + " [" + fr.getLocale() + "]");
                        try {
                            java.util.ResourceBundle.getBundle("greet.Missing");
                            System.out.println("found");
                        } catch (java.util.MissingResourceException e) {
                            System.out.println("missing " + e.getClassName());
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        Files.createDirectories(out.resolve("greet"));
        Files.createDirectories(out.resolve("data"));
        Files.writeString(out.resolve("greet/messages.txt"), "hello from a resource\n");
        Files.writeString(out.resolve("data/info.properties"), "name=nabu\n");
        Files.writeString(out.resolve("data/skip.txt"), "excluded\n");
        Files.writeString(out.resolve("other.txt"), "not configured\n");
        Files.writeString(out.resolve("greet/Labels_nl.properties"), "label=Hoi\n");
        Files.writeString(out.resolve("greet/Labels.properties"), "label=Hello\nbye=Bye\n");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("resource-config.json"), """
                {
                  "resources": {
                    "includes": [ { "pattern": "greet/.*\\\\.txt" }, { "glob": "data/**" } ],
                    "excludes": [ { "pattern": "data/skip\\\\.txt" } ]
                  },
                  "bundles": [ { "name": "greet.Labels" } ]
                }
                """);

        // A second occurrence of greet/messages.txt, in a jar on the class path.
        final Path jar = tempDir.resolve("lib.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("greet/messages.txt"));
            zip.write("from the jar\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.CLASS_PATH, jar.toString())
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
        assertEquals("""
                hello from a resource
                nabu
                null null
                resource /greet/messages.txt hello from a resource
                label=Hoi
                resource:/greet/messages.txt=hello from a resource;resource://1/greet/messages.txt=from the jar;
                Hoi Bye nl | Hallo Bye | Hello []
                missing greet.Missing_
                """, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

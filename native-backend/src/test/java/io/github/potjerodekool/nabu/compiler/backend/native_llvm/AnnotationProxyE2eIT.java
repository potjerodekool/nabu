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
 * Dynamic proxies natively, for the interface lists of a
 * {@code proxy-config.json} (as with GraalVM {@code native-image}): calls
 * reach the handler with a Method (name, types, toString), the result is
 * unboxed, a declared checked exception passes and an undeclared one is
 * wrapped, {@code Method.invoke} delegates to a real object (also for a
 * default method), and an interface list without configuration is refused.
 */
class AnnotationProxyE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void proxyOfAnnotationType() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                import java.lang.reflect.InvocationHandler;
                import java.lang.reflect.Proxy;

                public class Main {
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Marker {
                        String value();
                        int order() default 4;
                    }
                    public static void main(String[] args) throws Exception {
                        InvocationHandler h = (proxy, method, a) -> {
                            String name = method.getName();
                            if (name.equals("value")) return "x";
                            if (name.equals("order")) return 9;
                            if (name.equals("annotationType")) return Marker.class;
                            if (name.equals("toString")) return "@Marker(x)";
                            return null;
                        };
                        Object p = Proxy.newProxyInstance(Main.class.getClassLoader(), new Class<?>[] { Marker.class }, h);
                        Marker marker = (Marker) p;
                        System.out.println(marker.value() + " " + marker.order() + " " + p);
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("proxy-config.json"), """
                [ { "interfaces": [ "app.Main$Marker" ] } ]
                """);

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
                x 9 @Marker(x)
                """, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

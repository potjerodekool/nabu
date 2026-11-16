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
class ProxyInheritedMethodE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void configuredProxies() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                import java.lang.reflect.InvocationHandler;
                import java.lang.reflect.Method;
                import java.lang.reflect.Proxy;

                public class Main {
                    public interface Base { long count(); }
                    public interface Sub extends Base { String name(); }
                    public static void main(String[] args) throws Exception {
                        java.util.Set<Method> implementations = new java.util.HashSet<>();
                        for (Method m : Base.class.getDeclaredMethods()) implementations.add(m);
                        InvocationHandler h = (proxy, method, a) -> {
                            System.out.println(method.getName() + " " + method.getDeclaringClass().getSimpleName() + " "
                                    + implementations.contains(method));
                            return method.getName().equals("count") ? (Object) 5L : (Object) "n";
                        };
                        Sub p = (Sub) Proxy.newProxyInstance(Main.class.getClassLoader(), new Class<?>[] { Sub.class }, h);
                        System.out.println(p.count());
                        System.out.println(p.name());
                        Object wider = Proxy.newProxyInstance(Main.class.getClassLoader(),
                                new Class<?>[] { Sub.class, Base.class }, h);
                        System.out.println(((Base) wider).count());
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), """
                [ { "name": "app.Main$Sub", "allPublicMethods": true }, { "name": "app.Main$Base" } ]
                """);
        Files.writeString(config.resolve("proxy-config.json"), """
                [ { "interfaces": [ "app.Main$Sub" ] } ]
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
                count Base true
                5
                name Sub false
                n
                count Base true
                5
                """, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

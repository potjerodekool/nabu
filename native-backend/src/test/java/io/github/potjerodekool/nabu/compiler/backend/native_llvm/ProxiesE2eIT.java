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
class ProxiesE2eIT {

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
                    public interface Greeter {
                        String greet(String name);
                        int count();
                        default String twice(String s) { return s + s; }
                    }
                    public interface Closer {
                        void close() throws java.io.IOException;
                    }
                    static class Real implements Greeter {
                        public String greet(String name) { return "hi " + name; }
                        public int count() { return 3; }
                    }
                    static String seen = "";
                    public static void main(String[] args) throws Exception {
                        Real real = new Real();
                        InvocationHandler h = (proxy, method, a) -> {
                            String name = method.getName();
                            if (name.equals("count")) return 42;
                            if (name.equals("close")) throw new java.io.IOException("closed");
                            if (name.equals("toString")) return "proxy";
                            if (name.equals("greet")) {
                                seen = method.getDeclaringClass().getName() + " " + method.getParameterTypes()[0].getName()
                                        + " " + method.getReturnType().getName() + " " + method.getParameterCount() + " " + method;
                                if ("boom".equals(a[0])) throw new Exception("checked");
                            }
                            return method.invoke(real, a);
                        };
                        Object p = Proxy.newProxyInstance(Main.class.getClassLoader(),
                                new Class<?>[] { Greeter.class, Closer.class }, h);
                        Greeter g = (Greeter) p;
                        System.out.println(g.greet("ada") + " " + g.count() + " " + g.twice("x") + " " + p);
                        System.out.println(seen);
                        try {
                            ((Closer) p).close();
                        } catch (java.io.IOException e) {
                            System.out.println("io " + e.getMessage());
                        }
                        try {
                            g.greet("boom");
                        } catch (java.lang.reflect.UndeclaredThrowableException e) {
                            System.out.println("undeclared " + e.getCause().getMessage());
                        }
                        System.out.println(Proxy.isProxyClass(p.getClass()) + " " + (Proxy.getInvocationHandler(p) == h)
                                + " " + Proxy.isProxyClass(Main.class));
                        try {
                            Proxy.newProxyInstance(null, new Class<?>[] { Runnable.class }, h);
                            System.out.println("created");
                        } catch (IllegalArgumentException e) {
                            System.out.println("not configured");
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("proxy-config.json"), """
                [ { "interfaces": [ "app.Main$Greeter", "app.Main$Closer" ] } ]
                """);

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
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
                hi ada 42 xx proxy
                app.Main$Greeter java.lang.String java.lang.String 1 public abstract java.lang.String app.Main$Greeter.greet(java.lang.String)
                io closed
                undeclared checked
                true true false
                not configured
                """, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

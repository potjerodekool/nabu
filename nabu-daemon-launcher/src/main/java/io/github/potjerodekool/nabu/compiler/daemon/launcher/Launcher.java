package io.github.potjerodekool.nabu.compiler.daemon.launcher;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.Manifest;

/**
 * Bootstrap entry point for the nabu compiler daemon.
 * <p>
 * IMPORTANT: this file, and everything it statically references, must never
 * import an `org.bytedeco.*` class. The whole point is that the JVM has not
 * yet been given a classpath entry containing the native LLVM/JavaCPP
 * binaries when this class is verified/loaded. If any bytedeco class were
 * touched before [buildDaemonClassLoader] runs, JavaCPP's own Loader would
 * already have tried (and failed) to locate the native library.
 */
public class Launcher {

    private static final String llvmVersion = resolveLLMVersion();
    private static final String javacppVersion = manifestAttribute("Nabu-Javacpp-Version", "1.5.10");
    private static final String daemonMainClass = manifestAttribute("Nabu-Daemon-Main-Class", "io.github.potjerodekool.nabu.compiler.daemon.CompilerDaemon");

    public static void main(String[] args) {
        final var classifier = PlatformDetector.classifier();
        println(String.format("[nabu-launcher] platform detected: %s", classifier));

        final var fetcher = new NativeArtifactFetcher();

        final var llvmNative = fetcher.ensure("org/bytedeco", "llvm", llvmVersion, classifier);
        final var javacppNative = fetcher.ensure("org/bytedeco", "javacpp", javacppVersion, classifier);

        println("[nabu-launcher] native artifacts ready:");
        println(String.format("  - %s", llvmNative));
        println(String.format("  - %s", javacppNative));

        final var daemonClassLoader = buildDaemonClassLoader(llvmNative, javacppNative);
        invokeDaemonMain(daemonClassLoader, daemonMainClass, args);
    }

    private static URLClassLoader buildDaemonClassLoader(final Path... nativeJars) {
        final var urls = Arrays.stream(nativeJars)
                .map(Launcher::toUrl)
                .toArray(URL[]::new);

        // Parent is the launcher's own classloader, so the daemon's
        // non-bytedeco classes (already on the launcher's classpath, e.g.
        // nabu-daemon-core.jar) remain visible.
        return new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());
    }

    private static URL toUrl(final Path path) {
        try {
            return path.toUri().toURL();
        } catch (final MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void invokeDaemonMain(final URLClassLoader loader,
                                         final String mainClassName,
                                         final String[] args) {
        final var previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);

        try {
            final var mainClass = Class.forName(mainClassName, true, loader);
            final var mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, new Object[]{args});
        } catch (final ClassNotFoundException |
                       NoSuchMethodException |
                       IllegalAccessException |
                       InvocationTargetException e) {
            throw new IllegalStateException(
                    String.format("Failed to find or load %s. Is nabu-daemon-core.jar present on the classpath?", mainClassName),
                    e
            );
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static void println(final String msg) {
        System.out.println(msg);
    }

    private static String resolveLLMVersion() {
        final var packageObj = Launcher.class.getPackage();

        if (packageObj != null) {
            final var version = packageObj.getImplementationVersion();
            if (version != null) {
                return version;
            }
        }

        return manifestAttribute("Nabu-Llvm-Version", "17.0.6-1.5.10");
    }

    private static String manifestAttribute(final String name,
                                            final String defaultValue) {
        final var classLoader = Launcher.class.getClassLoader();

        try {
            final var resources = classLoader.getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                final var resource = resources.nextElement();
                try (var stream = resource.openStream()) {
                    final var value = new Manifest(stream).getMainAttributes().getValue(name);

                    if (value != null) {
                        return value;
                    }
                }
            }

        } catch (final IOException ignored) {
        }

        return defaultValue;
    }
}

package io.github.potjerodekool.nabu.cli;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** The {@code nabu} command. */
public final class Nabu {

    private static final String NATIVE_IMAGE = "io.github.potjerodekool.nabu.nativeimage.NativeImage";

    private final Settings settings;
    private final PrintStream out;
    private final PrintStream err;

    Nabu(final Settings settings, final PrintStream out, final PrintStream err) {
        this.settings = settings;
        this.out = out;
        this.err = err;
    }

    public static void main(final String[] args) {
        System.exit(new Nabu(Settings.fromSystem(), System.out, System.err).run(List.of(args)));
    }

    int run(final List<String> args) {
        if (args.isEmpty() || args.get(0).equals("-h") || args.get(0).equals("--help") || args.get(0).equals("help")) {
            out.println(usage());
            return args.isEmpty() ? 1 : 0;
        }
        final List<String> rest = args.subList(1, args.size());
        return switch (args.get(0)) {
            case "version", "--version", "-v" -> version();
            case "doctor" -> doctor();
            case "self-update" -> selfUpdate(rest);
            case "native-image" -> nativeImage(rest);
            default -> {
                err.println("nabu: unknown command: " + args.get(0));
                err.println(usage());
                yield 2;
            }
        };
    }

    static String usage() {
        return """
                Usage: nabu <command>

                  version            the version, the platform and whether the native backend is installed
                  doctor             check what Nabu needs on this machine and say what is missing
                  self-update        install the latest release (see: nabu self-update --help)
                  native-image ...   compile a Java program to a native executable (needs the native backend)
                """;
    }

    private int version() {
        out.println("nabu " + settings.version() + " (" + settings.platform() + ")");
        if (settings.home() != null) {
            out.println("  native backend: " + (settings.nativeInstalled(settings.version())
                    ? "installed" : "not installed (nabu self-update --native)"));
        }
        return 0;
    }

    private int doctor() {
        boolean ok = true;
        out.println("nabu " + settings.version());
        final int java = Runtime.version().feature();
        out.println("  java      " + System.getProperty("java.version") + " (" + System.getProperty("java.home") + ")"
                + (java >= 21 ? "" : "  TOO OLD: Nabu needs Java 21 or later (set NABU_JAVA_HOME or JAVA_HOME)"));
        ok &= java >= 21;
        out.println("  system    " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + "  [" + settings.platform() + "]");
        if (settings.home() == null) {
            out.println("  home      not started through the launcher (no nabu.home)");
        } else {
            out.println("  home      " + settings.home());
        }

        final boolean hasNative = settings.home() != null && settings.nativeInstalled(settings.version());
        if (!hasNative) {
            out.println("  native    not installed: nabu self-update --native (the JVM backend needs nothing more)");
            return ok ? 0 : 1;
        }
        out.println("  native    installed");
        ok &= windowsRuntime();
        final int nativeCode = invokeNativeImage(List.of("--doctor"));
        return ok && nativeCode == 0 ? 0 : 1;
    }

    /** The LLVM libraries of the native backend need the Microsoft Visual C++ runtime, which Nabu does not ship. */
    private boolean windowsRuntime() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return true;
        }
        for (final String library : List.of("vcruntime140", "msvcp140")) {
            try {
                System.loadLibrary(library);
            } catch (final UnsatisfiedLinkError e) {
                final String arch = settings.platform().endsWith("arm64") ? "arm64" : "x64";
                out.println("  " + library + "  MISSING: install the Microsoft Visual C++ Redistributable "
                        + "(https://aka.ms/vs/17/release/vc_redist." + arch + ".exe)");
                return false;
            }
        }
        out.println("  vc++      the Microsoft Visual C++ runtime is installed");
        return true;
    }

    private int selfUpdate(final List<String> args) {
        if (args.contains("--help") || args.contains("-h")) {
            out.println(SelfUpdate.usage());
            return 0;
        }
        final SelfUpdate.Options options = SelfUpdate.parse(args, err);
        if (options == null) {
            return 2;
        }
        return new SelfUpdate(settings, new Releases(settings.releasesUrl()), out, err).run(options);
    }

    private int nativeImage(final List<String> args) {
        return invokeNativeImage(args);
    }

    /** The native backend brings its own front door: {@code NativeImage.run(List, PrintStream, PrintStream)}. */
    private int invokeNativeImage(final List<String> args) {
        final Method run;
        try {
            run = Class.forName(NATIVE_IMAGE).getMethod("run", List.class, PrintStream.class, PrintStream.class);
        } catch (final ClassNotFoundException | NoClassDefFoundError e) {
            err.println("nabu: the native backend is not installed. Install it with: nabu self-update --native");
            return 1;
        } catch (final NoSuchMethodException e) {
            err.println("nabu: the native backend does not fit this nabu (" + e.getMessage() + "); "
                    + "run: nabu self-update");
            return 1;
        }
        try {
            return (Integer) run.invoke(null, args, out, err);
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof NoClassDefFoundError || cause instanceof UnsatisfiedLinkError) {
                err.println("nabu: the native backend cannot be loaded: " + cause);
                return 1;
            }
            err.println("nabu: " + cause);
            return 1;
        } catch (final IllegalAccessException e) {
            err.println("nabu: " + e);
            return 1;
        }
    }
}

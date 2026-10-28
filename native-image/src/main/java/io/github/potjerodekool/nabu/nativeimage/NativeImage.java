package io.github.potjerodekool.nabu.nativeimage;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.nativeimage.NativeImageArguments.UsageException;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code nabu-native-image}: compiles a Java program that comes from jars or class directories to a native executable
 * with the LLVM backend, the way GraalVM's {@code native-image} does. The program is found from its main class, and
 * the {@code META-INF/native-image} configuration of the jars on the class path is used.
 */
public final class NativeImage {

    private NativeImage() {
    }

    public static void main(final String[] args) {
        System.exit(run(List.of(args), System.out, System.err));
    }

    /** Runs the command and returns the exit code. */
    public static int run(final List<String> args, final PrintStream out, final PrintStream err) {
        final NativeImageArguments arguments;
        try {
            arguments = NativeImageArguments.parse(args);
        } catch (final UsageException e) {
            err.println("nabu-native-image: " + e.getMessage());
            return 2;
        }
        return switch (arguments.action()) {
            case HELP -> {
                out.print(usage());
                yield 0;
            }
            case VERSION -> {
                out.println("nabu-native-image " + version());
                yield 0;
            }
            case DOCTOR -> Doctor.run(out);
            case BUILD -> build(arguments, out, err);
        };
    }

    static String version() {
        final String version = NativeImage.class.getPackage().getImplementationVersion();
        return version != null ? version : "0.1.0-SNAPSHOT";
    }

    static String usage() {
        return """
                Usage: nabu-native-image [options] class [imagename]
                       nabu-native-image [options] -jar jarfile [imagename]

                Compiles a Java program to a native executable.

                Options:
                  -cp, --class-path <path>   jars and directories, separated by '%s'; dir/* adds the jars of dir
                  -jar <file>                the program is a jar: Main-Class and Class-Path of its manifest
                  -o <name>                  name of the executable (default: the class or the jar)
                  -d, --output-dir <dir>     where the executable is written (default: the current directory)
                  --gc <BOEHM|REFCOUNT|NONE> garbage collector (default BOEHM)
                  -O0 .. -O3, --opt <level>  optimization: NONE, DEFAULT or AGGRESSIVE (-O0 / -O1, -O2 / -O3)
                  --strict, --no-fallback    fail when the program or its libraries call a method without implementation
                  --static                   link the toolchain runtime into the executable (the default on Windows: the
                                             executable needs no MinGW DLLs)
                  --dynamic                  do not (Windows: the executable needs the MinGW DLLs on the PATH)
                  --keep-work                keep the directory with the intermediate files
                  --doctor                   check the toolchain this command needs
                  --version                  print the version
                  -h, --help                 this text

                Options of GraalVM's native-image that have no meaning here (-H:..., -R:..., --initialize-at-...,
                --no-fallback, ...) are accepted and ignored, with a warning, so existing command lines keep working.
                The configuration in META-INF/native-image of the jars is read as GraalVM reads it.
                """.formatted(java.io.File.pathSeparator);
    }

    private static int build(final NativeImageArguments arguments, final PrintStream out, final PrintStream err) {
        for (final String warning : arguments.warnings()) {
            err.println("nabu-native-image: warning: " + warning);
        }
        for (final Path entry : arguments.classPath()) {
            if (!Files.exists(entry)) {
                err.println("nabu-native-image: class path entry not found: " + entry);
                return 2;
            }
        }
        if (!ClassPaths.contains(arguments.classPath(), arguments.mainClass())) {
            err.println("nabu-native-image: class " + arguments.mainClass() + " not found on the class path");
            return 2;
        }

        final Path outputDir = arguments.outputDir().toAbsolutePath().normalize();
        final Path work = outputDir.resolve(".nabu-build-" + arguments.imageName());
        try {
            deleteTree(work);
            final Path sources = Files.createDirectories(work.resolve("src"));
            final Path classes = Files.createDirectories(work.resolve("out"));
            Files.createDirectories(outputDir);

            final String classPath = arguments.classPath().stream()
                    .map(p -> p.toAbsolutePath().toString())
                    .collect(Collectors.joining(java.io.File.pathSeparator));
            final CompilerOptions options = new CompilerOptions.CompilerOptionsBuilder()
                    .option(CompilerOption.SOURCE_PATH, sources.toString())
                    .option(CompilerOption.CLASS_OUTPUT, classes.toString())
                    .option(CompilerOption.CLASS_PATH, classPath)
                    .option(CompilerOption.BACKEND, "LLVM")
                    .option(CompilerOption.GC, arguments.gc())
                    .option(CompilerOption.OPT, arguments.opt())
                    .option(CompilerOption.MAIN_CLASS, arguments.mainClass())
                    .option(CompilerOption.IMAGE_NAME, arguments.imageName())
                    .build();

            final List<String> errors = new ArrayList<>();
            final NabuCompiler compiler = new NabuCompiler();
            compiler.setListener(diagnostic -> {
                final String message = diagnostic.getKind() + ": " + diagnostic.getMessage(Locale.getDefault());
                if (diagnostic.getKind() == io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic.Kind.ERROR) {
                    errors.add(message);
                }
                err.println(message);
            });
            if (arguments.staticLink()) {
                System.setProperty("nabu.link.static", "true");
            }
            out.println("Building " + arguments.mainClass() + " ...");
            final long start = System.nanoTime();
            final int result;
            try {
                result = compiler.compile(options);
            } catch (final RuntimeException e) {
                err.println("nabu-native-image: the build failed: " + e.getMessage());
                return 1;
            }
            if (result != 0) {
                err.println("nabu-native-image: the build failed"
                        + (errors.isEmpty() ? " (see the messages above)" : ""));
                return 1;
            }

            final String exeName = arguments.imageName() + executableSuffix();
            final Path built = find(classes, exeName);
            if (built == null) {
                err.println("nabu-native-image: no executable was produced; has " + arguments.mainClass()
                        + " a main method?");
                return 1;
            }
            if (arguments.strict()) {
                final List<String> missing = unresolvedOutsideTheJdk(
                        built.toAbsolutePath().getParent().resolve(arguments.imageName() + ".unresolved.txt"));
                if (!missing.isEmpty()) {
                    err.println("nabu-native-image: --strict: the program calls " + missing.size()
                            + " method(s) of its own or of its libraries that have no implementation (native "
                            + "methods?); they would stop it at run time:");
                    missing.stream().limit(30).forEach(m -> err.println("  " + m));
                    if (missing.size() > 30) {
                        err.println("  ... " + (missing.size() - 30) + " more");
                    }
                    return 1;
                }
            }
            final Path exe = outputDir.resolve(exeName);
            Files.copy(built, exe, StandardCopyOption.REPLACE_EXISTING);
            exe.toFile().setExecutable(true);
            final Path lib = built.toAbsolutePath().getParent().resolve("lib");
            if (Files.isDirectory(lib)) {
                copyTree(lib, outputDir.resolve("lib"));
            }
            // the reports the backend points to are kept next to the executable
            for (final String report : List.of(".unresolved.txt", ".warnings.txt")) {
                final Path reportFile = built.toAbsolutePath().getParent().resolve(arguments.imageName() + report);
                if (Files.isRegularFile(reportFile)) {
                    Files.copy(reportFile, outputDir.resolve(reportFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            out.printf("Built %s in %.1f s%n", exe, (System.nanoTime() - start) / 1e9);
            if (!arguments.keepWork()) {
                deleteTree(work);
            }
            return 0;
        } catch (final IOException e) {
            err.println("nabu-native-image: " + e.getMessage());
            return 1;
        }
    }

    /** Prefixes of the names (package.Class_member) of classes of the JDK and of the runtime. */
    private static final List<String> JDK_PREFIXES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "io.github.potjerodekool.nabu.");

    /**
     * The unresolved functions of the report (see NativeLLVMBackend) that belong to the program or its libraries. The
     * JDK itself always has some: its natives that the program never reaches in practice.
     */
    static List<String> unresolvedOutsideTheJdk(final Path report) throws IOException {
        if (!Files.isRegularFile(report)) {
            return List.of();
        }
        return Files.readAllLines(report).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .filter(name -> JDK_PREFIXES.stream().noneMatch(name::startsWith))
                .toList();
    }

    static String executableSuffix() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "";
    }

    private static Path find(final Path dir, final String fileName) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().equals(fileName) && Files.isRegularFile(p))
                    .findFirst().orElse(null);
        }
    }

    private static void copyTree(final Path from, final Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (final Path source : (Iterable<Path>) walk::iterator) {
                final Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(final Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}

package io.github.potjerodekool.nabu.nativeimage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/**
 * The command line of {@code nabu-native-image}, in the form of GraalVM's {@code native-image}:
 *
 * <pre>
 *   nabu-native-image [options] class [imagename]
 *   nabu-native-image [options] -jar jarfile [imagename]
 * </pre>
 *
 * @param classPath the entries of the class path, in order
 * @param mainClass the class with the {@code main} method (a name with dots)
 * @param imageName the name of the executable, without extension
 * @param outputDir the directory the executable is written to
 * @param gc        the garbage collector: BOEHM, REFCOUNT or NONE
 * @param opt       the optimization level: NONE, DEFAULT or AGGRESSIVE
 * @param strict    fail the build instead of producing a program that can fail on a missing JDK method
 * @param keepWork  keep the directory with the intermediate files
 * @param staticLink link the runtime of the toolchain into the executable (Windows/MinGW; the default there)
 * @param action    what to do
 * @param warnings  options that are accepted but have no effect
 */
public record NativeImageArguments(List<Path> classPath,
                                   String mainClass,
                                   String imageName,
                                   Path outputDir,
                                   String gc,
                                   String opt,
                                   boolean strict,
                                   boolean keepWork,
                                   boolean staticLink,
                                   Action action,
                                   List<String> warnings) {

    public enum Action { BUILD, HELP, VERSION, DOCTOR }

    /** A command line that cannot be used; the message is meant for the user. */
    public static final class UsageException extends Exception {
        public UsageException(final String message) {
            super(message);
        }
    }

    public static NativeImageArguments parse(final List<String> args) throws UsageException {
        return parse(args, System.getProperty("os.name", "").toLowerCase().contains("win"));
    }

    /**
     * @param windows on Windows the runtime of the toolchain is linked into the executable unless {@code --dynamic} is
     *                given: an executable that needs {@code libwinpthread-1.dll} from the PATH fails to start when
     *                another, older copy of that DLL comes first
     */
    static NativeImageArguments parse(final List<String> args, final boolean windows) throws UsageException {
        final List<Path> classPath = new ArrayList<>();
        final List<String> positional = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        Path jar = null;
        String imageName = null;
        Path outputDir = null;
        String gc = "BOEHM";
        String opt = "DEFAULT";
        boolean strict = false;
        boolean keepWork = false;
        boolean staticLink = windows;
        Action action = Action.BUILD;

        for (int i = 0; i < args.size(); i++) {
            final String arg = args.get(i);
            if (arg.equals("-h") || arg.equals("--help")) {
                action = Action.HELP;
            } else if (arg.equals("--version")) {
                action = Action.VERSION;
            } else if (arg.equals("--doctor")) {
                action = Action.DOCTOR;
            } else if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")) {
                addClassPath(classPath, value(args, ++i, arg));
            } else if (arg.startsWith("--class-path=")) {
                addClassPath(classPath, arg.substring("--class-path=".length()));
            } else if (arg.equals("-jar")) {
                jar = Path.of(value(args, ++i, arg));
            } else if (arg.equals("-o") || arg.equals("--output")) {
                imageName = value(args, ++i, arg);
            } else if (arg.equals("-d") || arg.equals("--output-dir")) {
                outputDir = Path.of(value(args, ++i, arg));
            } else if (arg.equals("--gc")) {
                gc = value(args, ++i, arg).toUpperCase();
            } else if (arg.startsWith("--gc=")) {
                gc = arg.substring("--gc=".length()).toUpperCase();
            } else if (arg.equals("--opt")) {
                opt = value(args, ++i, arg).toUpperCase();
            } else if (arg.matches("-O[0-3s]")) {
                opt = switch (arg.charAt(2)) {
                    case '0' -> "NONE";
                    case '3' -> "AGGRESSIVE";
                    default -> "DEFAULT";
                };
            } else if (arg.equals("--strict") || arg.equals("--no-fallback")) {
                strict = true;
            } else if (arg.equals("--keep-work")) {
                keepWork = true;
            } else if (arg.equals("--static")) {
                staticLink = true;
            } else if (arg.equals("--dynamic")) {
                staticLink = false;
            } else if (arg.equals("--verbose") || arg.equals("--install-exit-handlers")) {
                warnings.add(arg + " has no effect");
            } else if (arg.startsWith("-H:") || arg.startsWith("-R:") || arg.startsWith("-J")
                    || arg.startsWith("--initialize-at-") || arg.startsWith("-march=")
                    || arg.startsWith("--enable-") || arg.startsWith("--no-")
                    || arg.startsWith("-E") || arg.startsWith("--features=")) {
                warnings.add(arg + " is ignored");
            } else if (arg.startsWith("-")) {
                throw new UsageException("unknown option " + arg + " (see --help)");
            } else {
                positional.add(arg);
            }
        }

        if (action != Action.BUILD) {
            return new NativeImageArguments(classPath, null, imageName, outputDir, gc, opt, strict, keepWork,
                    staticLink, action, warnings);
        }

        String mainClass;
        if (jar != null) {
            if (!Files.isRegularFile(jar)) {
                throw new UsageException("jar file not found: " + jar);
            }
            final List<Path> fromJar = new ArrayList<>();
            fromJar.add(jar);
            mainClass = readManifest(jar, fromJar);
            if (mainClass == null) {
                throw new UsageException(jar + " has no Main-Class in its manifest");
            }
            classPath.addAll(0, fromJar);
            if (imageName == null && !positional.isEmpty()) {
                imageName = positional.removeFirst();
            }
            if (imageName == null) {
                final String file = jar.getFileName().toString();
                imageName = file.endsWith(".jar") ? file.substring(0, file.length() - ".jar".length()) : file;
            }
        } else {
            if (positional.isEmpty()) {
                throw new UsageException("no main class and no -jar given (see --help)");
            }
            mainClass = positional.removeFirst();
            if (imageName == null && !positional.isEmpty()) {
                imageName = positional.removeFirst();
            }
            if (imageName == null) {
                imageName = mainClass.substring(mainClass.lastIndexOf('.') + 1).toLowerCase();
            }
        }
        if (!positional.isEmpty()) {
            throw new UsageException("unexpected argument " + positional.getFirst() + " (see --help)");
        }
        if (classPath.isEmpty()) {
            classPath.add(Path.of("."));
        }
        mainClass = mainClass.replace('/', '.');
        if (!List.of("BOEHM", "REFCOUNT", "NONE").contains(gc)) {
            throw new UsageException("unknown --gc value " + gc + ", use BOEHM, REFCOUNT or NONE");
        }
        if (!List.of("NONE", "DEFAULT", "AGGRESSIVE").contains(opt)) {
            throw new UsageException("unknown --opt value " + opt + ", use NONE, DEFAULT or AGGRESSIVE");
        }
        return new NativeImageArguments(List.copyOf(classPath), mainClass, imageName,
                outputDir != null ? outputDir : Path.of("."), gc, opt, strict, keepWork, staticLink, action,
                List.copyOf(warnings));
    }

    private static String value(final List<String> args, final int index, final String option)
            throws UsageException {
        if (index >= args.size()) {
            throw new UsageException("option " + option + " needs a value");
        }
        return args.get(index);
    }

    private static void addClassPath(final List<Path> classPath, final String value) {
        for (final String entry : value.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            if (entry.endsWith("/*") || entry.endsWith("\\*")) {
                // a wildcard names the jars of a directory, as the java launcher does
                final Path dir = Path.of(entry.substring(0, entry.length() - 2));
                try (var jars = Files.list(dir)) {
                    jars.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(classPath::add);
                } catch (final IOException ignored) {
                    // a missing directory is reported when the class path is checked
                }
            } else {
                classPath.add(Path.of(entry));
            }
        }
    }

    /** Reads Main-Class and adds the Class-Path entries of the manifest (relative to the jar) to {@code entries}. */
    private static String readManifest(final Path jar, final List<Path> entries) throws UsageException {
        try (var file = new JarFile(jar.toFile())) {
            final var manifest = file.getManifest();
            if (manifest == null) {
                return null;
            }
            final var attributes = manifest.getMainAttributes();
            final String classPath = attributes.getValue("Class-Path");
            if (classPath != null) {
                final Path base = jar.toAbsolutePath().getParent();
                for (final String entry : classPath.trim().split("\\s+")) {
                    if (!entry.isEmpty()) {
                        entries.add(base.resolve(entry).normalize());
                    }
                }
            }
            return attributes.getValue("Main-Class");
        } catch (final IOException e) {
            throw new UsageException("cannot read " + jar + ": " + e.getMessage());
        }
    }
}

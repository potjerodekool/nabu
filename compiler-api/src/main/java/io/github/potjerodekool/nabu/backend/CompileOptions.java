package io.github.potjerodekool.nabu.backend;

import io.github.potjerodekool.nabu.tools.JavaVersion;

import java.nio.file.Path;
import java.util.List;

public record CompileOptions(
        OptLevel optLevel,
        boolean  debugInfo,
        String   targetTriple,
        GcStrategy gcStrategy,
        JavaVersion javaVersion,
        List<Path> classPath,
        String mainClass,
        String imageName
) {
    public CompileOptions {
        classPath = classPath != null ? List.copyOf(classPath) : List.of();
    }

    public CompileOptions(OptLevel optLevel,
                          boolean debugInfo,
                          String targetTriple,
                          GcStrategy gcStrategy,
                          JavaVersion javaVersion,
                          List<Path> classPath) {
        this(optLevel, debugInfo, targetTriple, gcStrategy, javaVersion, classPath, null, null);
    }

    public CompileOptions(OptLevel optLevel,
                          boolean debugInfo,
                          String targetTriple,
                          GcStrategy gcStrategy,
                          JavaVersion javaVersion) {
        this(optLevel, debugInfo, targetTriple, gcStrategy, javaVersion, List.of());
    }

    public enum OptLevel { NONE, DEFAULT, AGGRESSIVE }

    public enum GcStrategy {
        NONE,
        BOEHM,
        REFCOUNT
    }

    public static CompileOptions debug() {
        return new CompileOptions(OptLevel.NONE, true, null, GcStrategy.NONE, JavaVersion.MINIMAL_VERSION);
    }

    public static CompileOptions release() {
        return new CompileOptions(OptLevel.AGGRESSIVE, false, null, GcStrategy.NONE, JavaVersion.MINIMAL_VERSION);
    }

    public static CompileOptions defaults() {
        return new CompileOptions(OptLevel.DEFAULT, false, null, GcStrategy.NONE, JavaVersion.MINIMAL_VERSION);
    }

    public static CompileOptions forTarget(String triple) {
        return new CompileOptions(OptLevel.DEFAULT, false, triple, GcStrategy.NONE, JavaVersion.MINIMAL_VERSION);
    }

    public CompileOptions withJavaVersion(JavaVersion javaVersion) {
        return new CompileOptions(optLevel, debugInfo, targetTriple, gcStrategy, javaVersion, classPath, mainClass, imageName);
    }

    /** Het classpath (mappen en jars), o.a. voor de GraalVM-configuratie van libraries. */
    public CompileOptions withClassPath(List<Path> classPath) {
        return new CompileOptions(optLevel, debugInfo, targetTriple, gcStrategy, javaVersion, classPath, mainClass, imageName);
    }

    /**
     * The class whose {@code main} method starts the program (a name with dots, or null: the first class with one)
     * and the name of the executable (null: the name of that class).
     */
    public CompileOptions withEntry(String mainClass, String imageName) {
        return new CompileOptions(optLevel, debugInfo, targetTriple, gcStrategy, javaVersion, classPath, mainClass, imageName);
    }
}

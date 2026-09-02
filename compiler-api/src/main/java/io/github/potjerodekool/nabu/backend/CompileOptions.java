package io.github.potjerodekool.nabu.backend;

import io.github.potjerodekool.nabu.tools.JavaVersion;

public record CompileOptions(
        OptLevel optLevel,
        boolean  debugInfo,
        String   targetTriple,
        GcStrategy gcStrategy,
        JavaVersion javaVersion
) {
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
        return new CompileOptions(optLevel, debugInfo, targetTriple, gcStrategy, javaVersion);
    }
}

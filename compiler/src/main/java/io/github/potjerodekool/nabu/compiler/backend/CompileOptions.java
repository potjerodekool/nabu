package io.github.potjerodekool.nabu.compiler.backend;

public record CompileOptions(
        OptLevel optLevel,
        boolean  debugInfo,
        String   targetTriple,
        GcStrategy gcStrategy
) {
    public enum OptLevel { NONE, DEFAULT, AGGRESSIVE }

    public enum GcStrategy {
        NONE,
        BOEHM,
        REFCOUNT
    }

    public static CompileOptions debug() {
        return new CompileOptions(OptLevel.NONE, true, null, GcStrategy.NONE);
    }

    public static CompileOptions release() {
        return new CompileOptions(OptLevel.AGGRESSIVE, false, null, GcStrategy.NONE);
    }

    public static CompileOptions defaults() {
        return new CompileOptions(OptLevel.DEFAULT, false, null, GcStrategy.NONE);
    }

    public static CompileOptions forTarget(String triple) {
        return new CompileOptions(OptLevel.DEFAULT, false, triple, GcStrategy.NONE);
    }
}

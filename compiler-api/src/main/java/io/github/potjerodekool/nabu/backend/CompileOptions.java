package io.github.potjerodekool.nabu.backend;

public record CompileOptions(
        OptLevel optLevel,
        boolean  debugInfo,
        String   targetTriple
) {
    public enum OptLevel { NONE, DEFAULT, AGGRESSIVE }

    public static CompileOptions debug() {
        return new CompileOptions(OptLevel.NONE, true, null);
    }

    public static CompileOptions release() {
        return new CompileOptions(OptLevel.AGGRESSIVE, false, null);
    }

    public static CompileOptions defaults() {
        return new CompileOptions(OptLevel.DEFAULT, false, null);
    }

    public static CompileOptions forTarget(String triple) {
        return new CompileOptions(OptLevel.DEFAULT, false, triple);
    }
}

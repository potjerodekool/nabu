package io.github.potjerodekool.nabu.compiler.backend.native_llvm.gc;

import java.util.List;

/**
 * No collection: every object comes from the C heap and is never collected (runtime/gc_none.c). For programs that run
 * briefly, for tests, and as the baseline to measure another collector against.
 */
public final class NoGarbageCollector implements GarbageCollector {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public String allocateSymbol() {
        return "nabu_none_alloc";
    }

    @Override
    public String allocateAtomicSymbol() {
        return "nabu_none_alloc_atomic";
    }

    @Override
    public String runtimeSource() {
        return "gc_none.c";
    }

    @Override
    public List<String> compileFlags() {
        return List.of("-DNABU_GC_NONE");
    }

    @Override
    public List<String> linkFlags() {
        return List.of();
    }
}

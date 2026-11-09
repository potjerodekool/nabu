package io.github.potjerodekool.nabu.compiler.backend.native_llvm.gc;

import io.github.potjerodekool.nabu.compiler.backend.native_llvm.Linker;

import java.util.ArrayList;
import java.util.List;

/** The Boehm-Demers-Weiser conservative collector (libgc): runtime/gc_boehm.c. */
public final class BoehmGarbageCollector implements GarbageCollector {

    @Override
    public String name() {
        return "boehm";
    }

    @Override
    public String allocateSymbol() {
        return "GC_malloc";
    }

    @Override
    public String allocateAtomicSymbol() {
        return "GC_malloc_atomic";
    }

    @Override
    public String runtimeSource() {
        return "gc_boehm.c";
    }

    @Override
    public List<String> compileFlags() {
        final List<String> flags = new ArrayList<>();
        flags.add("-DNABU_GC_BOEHM");
        Linker.homebrewPrefix().ifPresent(prefix -> flags.add("-I" + prefix + "/include"));
        return flags;
    }

    @Override
    public List<String> linkFlags() {
        final List<String> flags = new ArrayList<>();
        Linker.homebrewPrefix().ifPresent(prefix -> flags.add("-L" + prefix + "/lib"));
        flags.add("-lgc");
        flags.add("-lpthread");
        return flags;
    }
}

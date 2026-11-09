package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.compiler.backend.native_llvm.gc.GarbageCollector;
import io.github.potjerodekool.nabu.compiler.backend.native_llvm.gc.GarbageCollectors;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every strategy has a collector, and the collector's C file exists and is part of the link. */
class GarbageCollectorsTest {

    @Test
    void everyStrategyHasACollectorNamedLikeIt() {
        for (final CompileOptions.GcStrategy strategy : CompileOptions.GcStrategy.values()) {
            final GarbageCollector gc = GarbageCollectors.of(strategy);
            assertEquals(strategy.name().toLowerCase(), gc.name());
            assertTrue(Files.exists(Path.of("..", "runtime", gc.runtimeSource())),
                    "runtime/" + gc.runtimeSource() + " is missing");
        }
    }

    @Test
    void boehmNamesItsAllocatorsAndLibrary() {
        final GarbageCollector gc = GarbageCollectors.of(CompileOptions.GcStrategy.BOEHM);
        assertEquals("GC_malloc", gc.allocateSymbol());
        assertEquals("GC_malloc_atomic", gc.allocateAtomicSymbol());
        assertTrue(gc.compileFlags().contains("-DNABU_GC_BOEHM"));
        assertTrue(gc.linkFlags().contains("-lgc"));
    }

    @Test
    void noneNeedsNoLibrary() {
        final GarbageCollector gc = GarbageCollectors.of(CompileOptions.GcStrategy.NONE);
        assertEquals("nabu_none_alloc", gc.allocateSymbol());
        assertTrue(gc.compileFlags().contains("-DNABU_GC_NONE"));
        assertTrue(gc.linkFlags().isEmpty());
    }

    @Test
    void theLinkCommandCarriesTheCollectorOfTheStrategy() throws Exception {
        final List<Path> objects = new ArrayList<>(List.of(Path.of("x.o")));
        final List<String> boehm = Linker.buildCommand(objects, Path.of("x.exe"), "x86_64-w64-windows-gnu",
                CompileOptions.GcStrategy.BOEHM);
        final List<String> none = Linker.buildCommand(objects, Path.of("x.exe"), "x86_64-w64-windows-gnu",
                CompileOptions.GcStrategy.NONE);
        assertTrue(boehm.contains("-DNABU_GC_BOEHM") && boehm.stream().anyMatch(s -> s.endsWith("gc_boehm.c")), boehm.toString());
        assertTrue(none.contains("-DNABU_GC_NONE") && none.stream().anyMatch(s -> s.endsWith("gc_none.c")), none.toString());
    }
}

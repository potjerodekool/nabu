package io.github.potjerodekool.nabu.compiler.backend.native_llvm.gc;

import java.util.List;

/**
 * A garbage collector that a native executable can be built with.
 *
 * <p>A collector has a Java side (this interface: what the compiler emits and how the program is linked) and a C side
 * (one file in {@code runtime/} that implements {@code nabu_gc.h}). The compiled code only calls the two allocation
 * symbols named here, so the code generator is the same for every collector; the rest of the runtime reaches the
 * collector through {@code nabu_gc.h}.</p>
 *
 * <p>To add a collector: write {@code runtime/gc_<name>.c} (the functions of {@code nabu_gc.h}, the two allocation
 * symbols and a constructor that starts the collector), implement this interface, register the instance in
 * {@link GarbageCollectors} (or list the class in a {@code META-INF/services} file) and give
 * {@code CompileOptions.GcStrategy} a constant of the same name.</p>
 */
public interface GarbageCollector {

    /** The name of the collector, as in {@code --gc}; the lower case name of its {@code GcStrategy}. */
    String name();

    /**
     * The symbol of the function the compiled code calls for zeroed memory that can hold pointers
     * ({@code void *f(size_t)}).
     */
    String allocateSymbol();

    /**
     * The symbol of the function the compiled code calls for memory without pointers (arrays of numbers):
     * {@code void *f(size_t)}. The memory is not cleared, the code clears it.
     */
    String allocateAtomicSymbol();

    /** The C source of the collector, a file next to {@code nabu_runtime.c}. */
    String runtimeSource();

    /** Options for compiling the runtime (a define that selects this collector in the C files, include paths). */
    List<String> compileFlags();

    /** Options for linking the program (the collector's library). */
    List<String> linkFlags();
}

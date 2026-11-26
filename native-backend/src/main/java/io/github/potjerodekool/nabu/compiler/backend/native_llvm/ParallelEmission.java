package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.util.CompileException;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTargetMachineRef;
import org.bytedeco.llvm.LLVM.LLVMTargetRef;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Emits the bodies of a large program on several threads, each worker with an LLVM context, module and emitters of
 * its own (a context is not thread safe). See docs/parallel-emission-design.md.
 *
 * <p>The primary worker is the one that the backend made: it emits the prelude for real (object model, tables,
 * constructors) and the first share of the bodies. Every secondary worker emits the same prelude into a module of its
 * own, because its bodies need the same types and values, then drops what that prelude defined
 * ({@link ChunkedCodegen#dropDefinitions}) and emits its share of the bodies into object files of its own. The
 * programs' modules are shared out in contiguous ranges of about the same number of instructions, so the result does
 * not depend on the timing of the threads (apart from the numbering of the array types and the rare symbol that two
 * IR functions share).</p>
 *
 * <p>All preludes run in parallel and finish before any body is emitted: the bodies change the IR (phi
 * elimination), the preludes read all of it.</p>
 */
final class ParallelEmission {

    private record Secondary(LLVMContextRef ctx, LLVMModuleRef mod, LLVMBuilderRef builder,
                             LLVMTargetMachineRef machine, LLVMModuleEmitter emitter, ChunkedCodegen chunker) {
    }

    private final List<Secondary> secondaries = new ArrayList<>();
    private final LLVMModuleEmitter primary;
    private final int workers;

    /** Number of emitting workers: {@code -Dnabu.llvm.emitWorkers=n}, 1 (the single thread of before) by default. */
    static int workers() {
        return Math.max(1, Integer.getInteger("nabu.llvm.emitWorkers", 1));
    }

    ParallelEmission(final LLVMModuleEmitter primary, final int workers) {
        this.primary = primary;
        this.workers = workers;
    }

    /** Makes the contexts, modules and emitters of the secondary workers. */
    void create(final String moduleName, final CompileOptions opts, final boolean discardNames, final String triple,
                final Path outputObj, final long chunkSize,
                final io.github.potjerodekool.nabu.backend.nativeimage.ReflectionRegistry registry,
                final String mainClass) throws CompileException {
        final Set<String> claimed = ConcurrentHashMap.newKeySet();
        primary.setClaimedSymbols(claimed);
        for (int w = 1; w < workers; w++) {
            final LLVMContextRef ctx = LLVMContextCreate();
            if (discardNames) {
                LLVMContextSetDiscardValueNames(ctx, 1);
            }
            final LLVMModuleRef mod = LLVMModuleCreateWithNameInContext(moduleName + ".w" + w, ctx);
            final LLVMBuilderRef builder = LLVMCreateBuilderInContext(ctx);
            final LLVMModuleEmitter emitter = new LLVMModuleEmitter(ctx, mod, builder, opts);
            emitter.setReflectionRegistry(registry);
            if (mainClass != null) {
                emitter.setMainClassName(mainClass);
            }
            emitter.setClaimedSymbols(claimed);
            final BytePointer err = new BytePointer();
            final LLVMTargetRef target = new LLVMTargetRef();
            if (LLVMGetTargetFromTriple(new BytePointer(triple), target, err) != 0) {
                throw new CompileException("Unknown target: " + err.getString());
            }
            final LLVMTargetMachineRef machine = LLVMCreateTargetMachine(target, new BytePointer(triple),
                    new BytePointer("generic"), new BytePointer(""), LLVMCodeGenLevelNone, LLVMRelocPIC,
                    LLVMCodeModelDefault);
            final String base = outputObj.getFileName().toString().replaceFirst("\\.o$", "");
            final ChunkedCodegen chunker = new ChunkedCodegen(mod, machine, triple,
                    outputObj.resolveSibling(base + ".w" + w + ".o"));
            emitter.setChunker(chunker, chunkSize);
            secondaries.add(new Secondary(ctx, mod, builder, machine, emitter, chunker));
        }
    }

    /**
     * Preludes in parallel, then the bodies in parallel, then the tail of the primary worker. Afterwards the
     * primary's chunker knows what all workers compiled and used (for the stubs of unresolved symbols).
     */
    void emit(final List<IRModule> modules) throws CompileException {
        final long[] ends = ranges(modules, workers);
        final ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            final Thread t = new Thread(r, "emit-worker");
            t.setDaemon(true);
            return t;
        });
        try {
            final long t0 = System.nanoTime();
            final List<Callable<Void>> preludes = new ArrayList<>();
            preludes.add(() -> {
                primary.emitPrelude(modules);
                return null;
            });
            for (final Secondary s : secondaries) {
                preludes.add(() -> {
                    s.emitter().emitPrelude(modules);
                    return null;
                });
            }
            runAll(pool, preludes);
            final long t1 = System.nanoTime();
            int w = 1;
            for (final Secondary s : secondaries) {
                s.emitter().becomeSecondary(primary, "w" + w++ + ".");
            }
            final List<Callable<Void>> bodies = new ArrayList<>();
            bodies.add(() -> {
                primary.emitBodies(modules, 0, (int) ends[0]);
                return null;
            });
            for (int k = 0; k < secondaries.size(); k++) {
                final Secondary s = secondaries.get(k);
                final int first = (int) ends[k];
                final int last = (int) ends[k + 1];
                bodies.add(() -> {
                    s.emitter().emitBodies(modules, first, last);
                    return null;
                });
            }
            runAll(pool, bodies);
            final long t2 = System.nanoTime();
            primary.emitTail();
            for (final Secondary s : secondaries) {
                s.chunker().note();
                primary.chunker().compiledFunctions().addAll(s.chunker().compiledFunctions());
                primary.chunker().usedDeclarations().addAll(s.chunker().usedDeclarations());
            }
            System.err.println("[ParallelEmission] " + workers + " workers: preludes " + (t1 - t0) / 1_000_000L
                    + " ms, bodies " + (t2 - t1) / 1_000_000L + " ms");
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Stubs for the unresolved functions that only a secondary worker declared (an ad-hoc external of one of its
     * bodies): the primary worker does not know them. Weak, so the stubs of several workers are one at the link.
     *
     * @return their names
     */
    List<String> stubUnresolved() {
        final Set<String> compiled = primary.chunker().compiledFunctions();
        final java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (final Secondary s : secondaries) {
            s.chunker().compiledFunctions().addAll(compiled);
            names.addAll(s.emitter().stubUnresolvedSymbols());
        }
        return new ArrayList<>(names);
    }

    /** Compiles what is left of the secondary workers and returns their object files. */
    List<Path> finish() throws CompileException {
        final ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, secondaries.size()));
        try {
            final List<Callable<Void>> jobs = new ArrayList<>();
            for (final Secondary s : secondaries) {
                jobs.add(() -> {
                    s.chunker().finish();
                    return null;
                });
            }
            runAll(pool, jobs);
        } finally {
            pool.shutdown();
        }
        final List<Path> objects = new ArrayList<>();
        for (final Secondary s : secondaries) {
            objects.addAll(s.chunker().objects());
            LLVMDisposeTargetMachine(s.machine());
            LLVMDisposeBuilder(s.builder());
            LLVMDisposeModule(s.mod());
            LLVMContextDispose(s.ctx());
        }
        secondaries.clear();
        return objects;
    }

    private static void runAll(final ExecutorService pool, final List<Callable<Void>> jobs) throws CompileException {
        final List<Future<Void>> futures = new ArrayList<>();
        for (final Callable<Void> job : jobs) {
            futures.add(pool.submit(job));
        }
        Throwable failure = null;
        for (final Future<Void> f : futures) {
            try {
                f.get();
            } catch (final ExecutionException e) {
                if (failure == null) {
                    failure = e.getCause();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompileException("Emission interrupted");
            }
        }
        if (failure != null) {
            if (failure instanceof CompileException ce) {
                throw ce;
            }
            if (failure instanceof RuntimeException re) {
                throw re;
            }
            if (failure instanceof Error er) {
                throw er;
            }
            throw new CompileException("Emission failed: " + failure);
        }
    }

    /**
     * The module index where each worker's range starts, and the module count at the end: ranges of contiguous
     * modules with about the same number of IR instructions.
     */
    static long[] ranges(final List<IRModule> modules, final int workers) {
        final long[] size = new long[modules.size()];
        long total = 0;
        for (int i = 0; i < size.length; i++) {
            long n = 0;
            for (final IRFunction fn : modules.get(i).functions()) {
                if (!fn.isExternal()) {
                    for (final var block : fn.blocks()) {
                        n += block.instructions().size();
                    }
                }
            }
            size[i] = n;
            total += n;
        }
        final long[] ends = new long[workers + 1];
        long cumulative = 0;
        int worker = 0;
        for (int i = 0; i < size.length && worker < workers - 1; i++) {
            if (cumulative >= total * (worker + 1) / workers) {
                ends[worker++] = i;
            }
            cumulative += size[i];
        }
        // ends[k] = first module of worker k+1, i.e. the last of worker k (exclusive)
        for (; worker < workers - 1; worker++) {
            ends[worker] = size.length;
        }
        ends[workers - 1] = size.length;
        ends[workers] = size.length;
        return ends;
    }
}

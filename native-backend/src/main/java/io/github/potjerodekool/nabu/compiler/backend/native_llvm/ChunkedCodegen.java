package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.util.CompileException;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMMemoryBufferRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTargetMachineRef;
import org.bytedeco.llvm.LLVM.LLVMTargetRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Generates the code of one large LLVM module in chunks while the module is still being built, so that the bodies of
 * the whole program are never in memory at once.
 *
 * <p>After a group of bodies has been emitted, {@link #flush} compiles the module to an object file in which only
 * those functions are defined and then throws their bodies away (the functions stay, as declarations, so the values
 * the emitter holds stay valid). {@link #finish} compiles what is left: the globals and the functions made last.
 * The object files link into the same program as the whole module would.</p>
 *
 * <ul>
 *   <li>Every private or internal symbol becomes external and hidden: another object file uses it.</li>
 *   <li>The initializers of the globals are taken away for the chunks (a global is a declaration there) and put back
 *   for the last object file, which is where the globals are defined.</li>
 *   <li>The data layout and target of the module are those of the machine only while the code is generated: the
 *   emitter takes its sizes from the module while it is building.</li>
 * </ul>
 */
final class ChunkedCodegen {

    private final LLVMModuleRef mod;
    private final LLVMTargetMachineRef machine;
    private final String triple;
    private final Path firstObject;
    private final List<Path> objects = new ArrayList<>();

    /** The globals whose initializer was taken away, with the initializer and the linkage they had. */
    private final List<Object[]> stripped = new ArrayList<>();

    /** Names of the functions that had a body when a chunk was compiled. */
    private final Set<String> compiled = new HashSet<>();
    /** Names of the declarations that were called or referenced while their callers were still there. */
    private final Set<String> usedDeclarations = new HashSet<>();

    /**
     * The object code of a chunk is made on another thread, from a copy of the module as bitcode in a context of its
     * own (a context is not thread safe), while this thread goes on with the bodies of the next chunk: 54 of the
     * 108 seconds of the bodies of the petstore were the code generator. -Dnabu.llvm.chunkThreads=0 does it in place.
     */
    private static final int THREADS = Integer.getInteger("nabu.llvm.chunkThreads", 2);
    private ExecutorService pool;
    private Semaphore inFlight;
    private final List<Future<?>> pending = new ArrayList<>();

    ChunkedCodegen(final LLVMModuleRef mod, final LLVMTargetMachineRef machine, final String triple,
                   final Path firstObject) {
        this.mod = mod;
        this.machine = machine;
        this.triple = triple;
        this.firstObject = firstObject;
    }

    /** Set for a secondary worker of a parallel emission: what its bodies create gets this prefix, see {@link #uniquify}. */
    private String prefix;
    /** Names of the functions and globals with local linkage that the prelude made (other workers use them by name). */
    private Set<String> preludeLocals = Set.of();

    /**
     * For a secondary worker, after its prelude: everything the prelude defined is defined by the primary worker, so
     * here it becomes a declaration for good (the bodies of the functions are removed, the initializers of the globals
     * are dropped and do not come back in {@link #finish}). The names of what the bodies create from now on get
     * {@code prefix}: every worker numbers its string literals, caches and slots on its own, and the object files
     * are linked into one program.
     *
     * @return the names of the functions that the prelude defined: no body may define them again
     */
    Set<String> dropDefinitions(final String prefix) {
        this.prefix = prefix;
        final Set<String> locals = new HashSet<>();
        final Set<String> defined = new HashSet<>();
        for (LLVMValueRef fn = LLVMGetFirstFunction(mod); fn != null && !fn.isNull(); fn = LLVMGetNextFunction(fn)) {
            if (LLVMIsDeclaration(fn) == 0) {
                defined.add(LLVMGetValueName(fn).getString());
            }
            if (isLocal(LLVMGetLinkage(fn))) {
                locals.add(LLVMGetValueName(fn).getString());
            }
        }
        final List<LLVMValueRef> toDelete = new ArrayList<>();
        for (LLVMValueRef g = LLVMGetFirstGlobal(mod); g != null && !g.isNull(); g = LLVMGetNextGlobal(g)) {
            final String name = LLVMGetValueName(g).getString();
            if (name.startsWith("llvm.")) {
                toDelete.add(g);
                continue;
            }
            if (isLocal(LLVMGetLinkage(g))) {
                locals.add(name);
            }
            final LLVMValueRef init = LLVMGetInitializer(g);
            if (init != null && !init.isNull()) {
                LLVMSetInitializer(g, null);
            }
        }
        // llvm.global_ctors, llvm.used: written once, by the primary worker
        for (final LLVMValueRef g : toDelete) {
            LLVMDeleteGlobal(g);
        }
        clearBodies();
        this.preludeLocals = locals;
        return defined;
    }

    /**
     * Gives what a secondary worker created after its prelude a name that cannot clash with the other workers' objects.
     * The once-wrapper of a class initializer and its flag are the same thing in every worker (the class is initialized
     * once): weak, so the linker keeps one. Other definitions with local linkage are the worker's own: prefixed.
     * Definitions with external linkage that a body made up (a static field outside the closure) are weak.
     */
    private void uniquify() {
        if (prefix == null) {
            return;
        }
        for (LLVMValueRef fn = LLVMGetFirstFunction(mod); fn != null && !fn.isNull(); fn = LLVMGetNextFunction(fn)) {
            if (LLVMIsDeclaration(fn) != 0) {
                continue;
            }
            final int linkage = LLVMGetLinkage(fn);
            if (!isLocal(linkage)) {
                continue;
            }
            final String name = LLVMGetValueName(fn).getString();
            if (name.endsWith(".clinit.once")) {
                LLVMSetLinkage(fn, LLVMWeakAnyLinkage);
                LLVMSetVisibility(fn, LLVMHiddenVisibility);
            } else if (!preludeLocals.contains(name) && !name.startsWith(prefix)) {
                LLVMSetValueName2(fn, prefix + name, (prefix + name).length());
            }
        }
        for (LLVMValueRef g = LLVMGetFirstGlobal(mod); g != null && !g.isNull(); g = LLVMGetNextGlobal(g)) {
            final LLVMValueRef init = LLVMGetInitializer(g);
            final boolean defined = init != null && !init.isNull();
            final int linkage = LLVMGetLinkage(g);
            final String name = LLVMGetValueName(g).getString();
            if (defined) {
                if (name.endsWith(".clinit.done")) {
                    LLVMSetLinkage(g, LLVMWeakAnyLinkage);
                    LLVMSetVisibility(g, LLVMHiddenVisibility);
                } else if (isLocal(linkage) && !preludeLocals.contains(name) && !name.startsWith(prefix)) {
                    LLVMSetValueName2(g, prefix + name, (prefix + name).length());
                } else if (linkage == LLVMExternalLinkage) {
                    LLVMSetLinkage(g, LLVMWeakAnyLinkage);
                }
            }
        }
    }

    Set<String> compiledFunctions() {
        return compiled;
    }

    Set<String> usedDeclarations() {
        return usedDeclarations;
    }

    List<Path> objects() {
        return objects;
    }

    private Path nextObject() {
        final Path path = objects.isEmpty() ? firstObject
                : firstObject.resolveSibling(firstObject.getFileName().toString().replaceFirst("\\.o$", "")
                        + "." + objects.size() + ".o");
        objects.add(path);
        return path;
    }

    /** Compiles the functions that have a body now and drops their bodies. */
    void flush() throws CompileException {
        final long t0 = System.nanoTime();
        note();
        uniquify();
        internalize();
        strip();
        final long t1 = System.nanoTime();
        final Path output = nextObject();
        if (THREADS > 0) {
            generateAsync(output);
        } else {
            generate(output);
        }
        final long t2 = System.nanoTime();
        clearBodies();
        if (Boolean.getBoolean("nabu.llvm.phaseTiming")) {
            System.err.println("[ChunkedCodegen] flush " + objects.size() + ": prepare " + (t1 - t0) / 1_000_000
                    + " ms, object code " + (t2 - t1) / 1_000_000 + " ms, clear bodies "
                    + (System.nanoTime() - t2) / 1_000_000 + " ms");
        }
    }

    /** Waits for the object files that are being made. */
    private void await() throws CompileException {
        try {
            for (final Future<?> f : pending) {
                f.get();
            }
        } catch (ExecutionException e) {
            throw e.getCause() instanceof CompileException ce ? ce : new CompileException("Codegen failed: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompileException("Codegen interrupted");
        } finally {
            pending.clear();
            if (pool != null) {
                pool.shutdown();
                pool = null;
            }
        }
    }

    /** Compiles the rest: the globals (with their initializers) and the functions that are still defined. */
    void finish() throws CompileException {
        uniquify();
        internalize();
        for (final Object[] each : stripped) {
            final LLVMValueRef global = (LLVMValueRef) each[0];
            LLVMSetInitializer(global, (LLVMValueRef) each[1]);
            LLVMSetLinkage(global, (Integer) each[2]);
        }
        stripped.clear();
        generate(nextObject());
        await();
    }

    /** Notes the defined functions and the used declarations of the module as it is now. */
    void note() {
        for (LLVMValueRef fn = LLVMGetFirstFunction(mod); fn != null && !fn.isNull(); fn = LLVMGetNextFunction(fn)) {
            if (LLVMGetIntrinsicID(fn) != 0) {
                continue;
            }
            if (LLVMIsDeclaration(fn) == 0) {
                compiled.add(LLVMGetValueName(fn).getString());
            } else {
                final var use = LLVMGetFirstUse(fn);
                if (use != null && !use.isNull()) {
                    usedDeclarations.add(LLVMGetValueName(fn).getString());
                }
            }
        }
    }

    private static boolean isLocal(final int linkage) {
        return linkage == LLVMInternalLinkage || linkage == LLVMPrivateLinkage;
    }

    private void internalize() {
        for (LLVMValueRef fn = LLVMGetFirstFunction(mod); fn != null && !fn.isNull(); fn = LLVMGetNextFunction(fn)) {
            if (isLocal(LLVMGetLinkage(fn))) {
                LLVMSetLinkage(fn, LLVMExternalLinkage);
                LLVMSetVisibility(fn, LLVMHiddenVisibility);
            }
        }
        for (LLVMValueRef g = LLVMGetFirstGlobal(mod); g != null && !g.isNull(); g = LLVMGetNextGlobal(g)) {
            if (isLocal(LLVMGetLinkage(g))) {
                LLVMSetLinkage(g, LLVMExternalLinkage);
                LLVMSetVisibility(g, LLVMHiddenVisibility);
            }
        }
    }

    /** Makes the globals declarations for the chunks. The ones that are new since the last time are taken. */
    private void strip() {
        for (LLVMValueRef g = LLVMGetFirstGlobal(mod); g != null && !g.isNull(); g = LLVMGetNextGlobal(g)) {
            final LLVMValueRef init = LLVMGetInitializer(g);
            if (init == null || init.isNull()) {
                continue;
            }
            final int linkage = LLVMGetLinkage(g);
            stripped.add(new Object[]{g, init, linkage});
            LLVMSetInitializer(g, null);
            if (linkage == LLVMAppendingLinkage || linkage == LLVMWeakAnyLinkage || linkage == LLVMWeakODRLinkage
                    || linkage == LLVMLinkOnceAnyLinkage || linkage == LLVMLinkOnceODRLinkage) {
                // llvm.global_ctors and the like: written once, with the last object file; a weak global is
                // only a declaration (external) while its initializer is away
                LLVMSetLinkage(g, LLVMExternalLinkage);
            }
        }
    }

    /**
     * Writes the module as bitcode and compiles that on a thread of the pool. At most two chunks are being compiled or are waiting.
     */
    private void generateAsync(final Path output) throws CompileException {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(THREADS, r -> {
                final Thread t = new Thread(r, "chunk-codegen");
                t.setDaemon(true);
                return t;
            });
            inFlight = new Semaphore(THREADS);
        }
        inFlight.acquireUninterruptibly();
        final LLVMMemoryBufferRef buffer;
        try {
            final long w0 = System.nanoTime();
            buffer = LLVMWriteBitcodeToMemoryBuffer(mod);
            if (Boolean.getBoolean("nabu.llvm.phaseTiming")) {
                System.err.println("[ChunkedCodegen] bitcode " + LLVMGetBufferSize(buffer) / 1024 / 1024 + " MB in "
                        + (System.nanoTime() - w0) / 1_000_000 + " ms");
            }
        } finally {
            LLVMSetDataLayout(mod, new BytePointer(""));
            LLVMSetTarget(mod, new BytePointer(""));
        }
        pending.add(pool.submit(() -> {
            try {
                compileBitcode(buffer, output);
            } finally {
                LLVMDisposeMemoryBuffer(buffer);
                inFlight.release();
            }
            return null;
        }));
    }

    private void compileBitcode(final LLVMMemoryBufferRef buffer, final Path output) throws CompileException {
        final LLVMContextRef context = LLVMContextCreate();
        LLVMTargetMachineRef threadMachine = null;
        try {
            final LLVMModuleRef copy = new LLVMModuleRef();
            if (LLVMParseBitcodeInContext2(context, buffer, copy) != 0) {
                throw new CompileException("Codegen failed: the chunk could not be read back");
            }
            final BytePointer err = new BytePointer();
            final LLVMTargetRef target = new LLVMTargetRef();
            if (LLVMGetTargetFromTriple(new BytePointer(triple), target, err) != 0) {
                throw new CompileException("Unknown target: " + err.getString());
            }
            threadMachine = LLVMCreateTargetMachine(target, new BytePointer(triple), new BytePointer("generic"),
                    new BytePointer(""), LLVMCodeGenLevelNone, LLVMRelocPIC, LLVMCodeModelDefault);
            // set here and not before the bitcode is written: with a target the writer also builds a symbol table
            LLVMSetTarget(copy, new BytePointer(triple));
            final var layout = LLVMCreateTargetDataLayout(threadMachine);
            LLVMSetModuleDataLayout(copy, layout);
            LLVMDisposeTargetData(layout);
            if (LLVMTargetMachineEmitToFile(threadMachine, copy, new BytePointer(output.toString()), LLVMObjectFile,
                    err) != 0) {
                throw new CompileException("Codegen failed: " + err.getString());
            }
        } finally {
            if (threadMachine != null) {
                LLVMDisposeTargetMachine(threadMachine);
            }
            LLVMContextDispose(context);
        }
    }

    private void generate(final Path output) throws CompileException {
        LLVMSetTarget(mod, new BytePointer(triple));
        final BytePointer err = new BytePointer();
        try {
            if (LLVMTargetMachineEmitToFile(machine, mod, new BytePointer(output.toString()), LLVMObjectFile, err) != 0) {
                throw new CompileException("Codegen failed: " + err.getString());
            }
        } finally {
            // while it builds, the emitter takes the sizes of types from the module: the default layout
            LLVMSetDataLayout(mod, new BytePointer(""));
            LLVMSetTarget(mod, new BytePointer(""));
        }
    }

    /** Turns every function with a body into a declaration. */
    private void clearBodies() {
        for (LLVMValueRef fn = LLVMGetFirstFunction(mod); fn != null && !fn.isNull(); fn = LLVMGetNextFunction(fn)) {
            if (LLVMIsDeclaration(fn) != 0) {
                continue;
            }
            final List<LLVMValueRef> instructions = new ArrayList<>();
            final List<LLVMBasicBlockRef> blocks = new ArrayList<>();
            for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(fn); bb != null && !bb.isNull();
                 bb = LLVMGetNextBasicBlock(bb)) {
                blocks.add(bb);
                for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null && !inst.isNull();
                     inst = LLVMGetNextInstruction(inst)) {
                    instructions.add(inst);
                }
            }
            // Uses first (a value can be used in a block that comes before it), then the instructions from the back.
            for (final LLVMValueRef inst : instructions) {
                final var type = LLVMTypeOf(inst);
                if (LLVMGetTypeKind(type) != LLVMVoidTypeKind) {
                    LLVMReplaceAllUsesWith(inst, LLVMGetUndef(type));
                }
            }
            for (int i = instructions.size() - 1; i >= 0; i--) {
                LLVMInstructionEraseFromParent(instructions.get(i));
            }
            for (final LLVMBasicBlockRef bb : blocks) {
                LLVMDeleteBasicBlock(bb);
            }
            LLVMSetSubprogram(fn, null);
            // a declaration must not have a personality routine (the verifier refuses the module)
            if (LLVMHasPersonalityFn(fn) != 0) {
                LLVMSetPersonalityFn(fn, null);
            }
        }
    }
}

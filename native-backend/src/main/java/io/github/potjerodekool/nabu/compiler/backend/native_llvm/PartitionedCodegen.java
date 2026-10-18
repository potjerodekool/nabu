package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.util.CompileException;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.SizeTPointer;
import org.bytedeco.llvm.LLVM.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Optimizes and compiles one large LLVM module as several partitions in
 * parallel, each in its own LLVM context and on its own thread (LLVM's
 * optimizer and code generator are single-threaded per module).
 *
 * <p>The module is split by function: every defined function is owned by
 * exactly one partition, assigned in module order (so the functions of one
 * class stay together) with roughly equal instruction counts. A global
 * variable is owned by the partition of its first user. In each partition the
 * functions and globals of other partitions become declarations, so the
 * partitions' object files link into the same program as the whole module.</p>
 *
 * <ul>
 *   <li>A private or internal symbol that is used from another partition is
 *   made external (hidden, with a {@code .xp} suffix so it cannot clash with
 *   a runtime symbol).</li>
 *   <li>{@code unnamed_addr} constants without references (string data) are
 *   copied into every partition that uses them instead.</li>
 *   <li>Small functions are also copied into every other partition as
 *   {@code available_externally}, so cross-partition calls to them can still
 *   be inlined (as ThinLTO's function import does). The optimizer drops those
 *   copies before code generation.</li>
 *   <li>{@code @llvm.global_ctors} stays in partition 0 only.</li>
 * </ul>
 *
 * <p>The split happens on bitcode: the prepared module is written once to a
 * memory buffer and every partition parses its own copy.</p>
 */
final class PartitionedCodegen {

    /** Functions with at most this many instructions are copied for inlining. */
    private static final int IMPORT_LIMIT = 40;

    private static final String XP_SUFFIX = ".xp";

    private static final boolean TIMING = Boolean.getBoolean("nabu.llvm.partitionTiming");

    private final int partitions;
    private final Map<Long, Integer> functionOwner = new HashMap<>();
    private final Map<Long, Integer> globalOwner = new HashMap<>();
    private final Map<String, Integer> functionOwnerByName = new HashMap<>();
    private final Map<String, Integer> globalOwnerByName = new HashMap<>();
    private final Set<String> duplicable = new HashSet<>();
    /** Small functions copied into other partitions: name → those partitions. */
    private final Map<String, BitSet> imported = new HashMap<>();

    private PartitionedCodegen(final int partitions) {
        this.partitions = partitions;
    }

    /**
     * The number of partitions for a module with {@code definedFunctions}
     * function bodies: 1 for small modules (unit tests), otherwise the
     * system property {@code nabu.llvm.partitions} or half the number of
     * logical processors, at most 16. (On an 8-core machine with
     * hyper-threading, 8 partitions are clearly faster than 16: the
     * partitions compete for the same cores and memory bandwidth.)
     */
    static int partitionCount(final int definedFunctions) {
        final String configured = System.getProperty("nabu.llvm.partitions");
        if (configured != null) {
            try {
                return Math.max(1, Integer.parseInt(configured.trim()));
            } catch (final NumberFormatException e) {
                // fall through to the default
            }
        }
        if (definedFunctions < 2000) {
            return 1;
        }
        return Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors() / 2));
    }

    static int countDefinedFunctions(final LLVMModuleRef mod) {
        int count = 0;
        for (LLVMValueRef f = LLVMGetFirstFunction(mod); f != null && !f.isNull(); f = LLVMGetNextFunction(f)) {
            if (LLVMIsDeclaration(f) == 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Splits {@code mod} into {@code partitions} parts, optimizes them with
     * {@code pipeline} and writes one object file per part. {@code mod} is
     * changed (symbols made external) but stays valid.
     *
     * @return the object files, partition 0 first
     */
    static List<Path> compile(final LLVMModuleRef mod,
                              final int partitions,
                              final String triple,
                              final String pipeline,
                              final List<Path> objectFiles) throws CompileException {
        final var codegen = new PartitionedCodegen(partitions);
        final long t0 = System.nanoTime();
        codegen.assignOwners(mod);
        final LLVMMemoryBufferRef bitcode = LLVMWriteBitcodeToMemoryBuffer(mod);
        final long t1 = System.nanoTime();
        System.err.println("[NativeLLVMBackend] split into " + partitions + " partitions ("
                + codegen.imported.size() + " small functions copied): "
                + (t1 - t0) / 1_000_000L + " ms");
        try {
            codegen.compilePartitions(bitcode, triple, pipeline, objectFiles);
        } finally {
            LLVMDisposeMemoryBuffer(bitcode);
        }
        return objectFiles;
    }

    // -------------------------------------------------------
    // Ownership (on the whole module)
    // -------------------------------------------------------

    private void assignOwners(final LLVMModuleRef mod) {
        // Functions: contiguous chunks of roughly equal size, in module order.
        final List<LLVMValueRef> defined = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        long total = 0;
        for (LLVMValueRef f = LLVMGetFirstFunction(mod); f != null && !f.isNull(); f = LLVMGetNextFunction(f)) {
            if (LLVMIsDeclaration(f) == 0) {
                final int size = instructionCount(f);
                defined.add(f);
                sizes.add(size);
                total += size;
            }
        }
        final double perPartition = (double) total / partitions;
        long running = 0;
        for (int i = 0; i < defined.size(); i++) {
            final int partition = (int) Math.min(partitions - 1, (long) (running / perPartition));
            functionOwner.put(defined.get(i).address(), partition);
            running += sizes.get(i);
        }

        // Globals: the partition of their first user; partition 0 if unused.
        final List<LLVMValueRef> globals = new ArrayList<>();
        for (LLVMValueRef g = LLVMGetFirstGlobal(mod); g != null && !g.isNull(); g = LLVMGetNextGlobal(g)) {
            if (LLVMIsDeclaration(g) == 0) {
                globals.add(g);
                if (isLocal(g) && LLVMIsGlobalConstant(g) != 0
                        && LLVMGetUnnamedAddress(g) == LLVMGlobalUnnamedAddr
                        && LLVMIsAConstantDataSequential(LLVMGetInitializer(g)) != null) {
                    duplicable.add(name(g));
                }
            }
        }
        for (final LLVMValueRef g : globals) {
            ownerOfGlobal(g);
        }

        // Local symbols used from another partition become external.
        for (final LLVMValueRef f : defined) {
            if (isLocal(f) && usedOutside(f, functionOwner.get(f.address()))) {
                externalize(f);
            }
        }
        for (final LLVMValueRef g : globals) {
            if (isLocal(g) && !duplicable.contains(name(g))
                    && usedOutside(g, globalOwner.get(g.address()))) {
                externalize(g);
            }
        }

        for (final LLVMValueRef f : defined) {
            functionOwnerByName.put(name(f), functionOwner.get(f.address()));
        }
        chooseImports(defined, sizes);
        for (final LLVMValueRef g : globals) {
            globalOwnerByName.put(name(g), globalOwner.get(g.address()));
        }
    }

    /**
     * Copies a small function into the partitions that use it, also through
     * another copied function (after inlining that one, its calls are there).
     */
    private void chooseImports(final List<LLVMValueRef> defined, final List<Integer> sizes) {
        final Map<Long, BitSet> users = new HashMap<>();
        for (int i = 0; i < defined.size(); i++) {
            final LLVMValueRef f = defined.get(i);
            if (sizes.get(i) <= IMPORT_LIMIT && isImportable(f)) {
                users.put(f.address(), new BitSet(partitions));
            }
        }
        final Map<Long, List<Long>> importedCallers = new HashMap<>();
        for (final LLVMValueRef f : defined) {
            final BitSet parts = users.get(f.address());
            if (parts == null) {
                continue;
            }
            for (LLVMUseRef u = LLVMGetFirstUse(f); u != null && !u.isNull(); u = LLVMGetNextUse(u)) {
                final LLVMValueRef user = LLVMGetUser(u);
                if (LLVMIsAInstruction(user) == null) {
                    continue; // a vtable entry is no inlining opportunity
                }
                final LLVMValueRef caller = LLVMGetBasicBlockParent(LLVMGetInstructionParent(user));
                parts.set(functionOwner.getOrDefault(caller.address(), 0));
                if (users.containsKey(caller.address()) && caller.address() != f.address()) {
                    importedCallers.computeIfAbsent(f.address(), k -> new ArrayList<>()).add(caller.address());
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (final var entry : importedCallers.entrySet()) {
                final BitSet parts = users.get(entry.getKey());
                final int before = parts.cardinality();
                for (final Long caller : entry.getValue()) {
                    parts.or(users.get(caller));
                }
                changed |= parts.cardinality() != before;
            }
        }
        for (final LLVMValueRef f : defined) {
            final BitSet parts = users.get(f.address());
            if (parts != null) {
                parts.clear(functionOwner.get(f.address()));
                if (!parts.isEmpty()) {
                    imported.put(name(f), parts);
                }
            }
        }
    }

    private static int instructionCount(final LLVMValueRef f) {
        int count = 0;
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(f); bb != null && !bb.isNull(); bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef i = LLVMGetFirstInstruction(bb); i != null && !i.isNull(); i = LLVMGetNextInstruction(i)) {
                count++;
            }
        }
        return count;
    }

    private int ownerOfGlobal(final LLVMValueRef g) {
        final Integer known = globalOwner.get(g.address());
        if (known != null) {
            return known;
        }
        globalOwner.put(g.address(), 0); // guards against cycles through initializers
        final int owner = firstUserPartition(g);
        globalOwner.put(g.address(), owner < 0 ? 0 : owner);
        return owner < 0 ? 0 : owner;
    }

    /** The partition of the first user of {@code value}, or -1 without users. */
    private int firstUserPartition(final LLVMValueRef value) {
        for (LLVMUseRef u = LLVMGetFirstUse(value); u != null && !u.isNull(); u = LLVMGetNextUse(u)) {
            final int partition = partitionOfUser(LLVMGetUser(u));
            if (partition >= 0) {
                return partition;
            }
        }
        return -1;
    }

    private int partitionOfUser(final LLVMValueRef user) {
        if (LLVMIsAInstruction(user) != null) {
            final LLVMValueRef fn = LLVMGetBasicBlockParent(LLVMGetInstructionParent(user));
            return functionOwner.getOrDefault(fn.address(), 0);
        }
        if (LLVMIsAFunction(user) != null) {
            return functionOwner.getOrDefault(user.address(), 0);
        }
        if (LLVMIsAGlobalVariable(user) != null) {
            return ownerOfGlobal(user);
        }
        if (LLVMIsAConstant(user) != null) {
            return firstUserPartition(user);
        }
        return -1;
    }

    private boolean usedOutside(final LLVMValueRef value, final int owner) {
        for (LLVMUseRef u = LLVMGetFirstUse(value); u != null && !u.isNull(); u = LLVMGetNextUse(u)) {
            final LLVMValueRef user = LLVMGetUser(u);
            if (LLVMIsAInstruction(user) != null || LLVMIsAFunction(user) != null
                    || LLVMIsAGlobalVariable(user) != null) {
                if (partitionOfUser(user) != owner) {
                    return true;
                }
            } else if (LLVMIsAConstant(user) != null && usedOutside(user, owner)) {
                return true;
            }
        }
        return false;
    }

    private static void externalize(final LLVMValueRef value) {
        LLVMSetValueName2(value, name(value) + XP_SUFFIX, name(value).length() + XP_SUFFIX.length());
        LLVMSetLinkage(value, LLVMExternalLinkage);
        LLVMSetVisibility(value, LLVMHiddenVisibility);
    }

    /**
     * A function can be copied into other partitions if it only refers to
     * symbols every partition can see: external symbols and duplicable
     * constants.
     */
    private boolean isImportable(final LLVMValueRef f) {
        if (isLocal(f)) {
            return false;
        }
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(f); bb != null && !bb.isNull(); bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef i = LLVMGetFirstInstruction(bb); i != null && !i.isNull(); i = LLVMGetNextInstruction(i)) {
                if (!onlyVisibleReferences(i, 0)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean onlyVisibleReferences(final LLVMValueRef value, final int depth) {
        final int operands = LLVMGetNumOperands(value);
        for (int k = 0; k < operands; k++) {
            final LLVMValueRef op = LLVMGetOperand(value, k);
            if (op == null || op.isNull() || LLVMValueIsBasicBlock(op) != 0) {
                continue;
            }
            if (LLVMIsAGlobalValue(op) != null) {
                if (isLocal(op) && !duplicable.contains(name(op))) {
                    return false;
                }
            } else if (LLVMIsAConstant(op) != null && LLVMGetNumOperands(op) > 0) {
                if (depth > 8 || !onlyVisibleReferences(op, depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    // -------------------------------------------------------
    // Per partition (own context, own thread)
    // -------------------------------------------------------

    private void compilePartitions(final LLVMMemoryBufferRef bitcode,
                                   final String triple,
                                   final String pipeline,
                                   final List<Path> objectFiles) throws CompileException {
        final BytePointer start = LLVMGetBufferStart(bitcode);
        final long size = LLVMGetBufferSize(bitcode);
        final ExecutorService pool = Executors.newFixedThreadPool(partitions, r -> {
            final Thread t = new Thread(r, "nabu-llvm-codegen");
            t.setDaemon(true);
            return t;
        });
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (int p = 0; p < partitions; p++) {
                final int partition = p;
                futures.add(pool.submit(() -> {
                    compilePartition(partition, start, size, triple, pipeline, objectFiles.get(partition));
                    return null;
                }));
            }
            for (final Future<?> future : futures) {
                try {
                    future.get();
                } catch (final ExecutionException e) {
                    if (e.getCause() instanceof CompileException ce) {
                        throw ce;
                    }
                    throw new CompileException("Partitioned code generation failed: " + e.getCause());
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompileException("Partitioned code generation interrupted");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void compilePartition(final int partition,
                                  final BytePointer start,
                                  final long size,
                                  final String triple,
                                  final String pipeline,
                                  final Path objectFile) throws CompileException {
        final LLVMContextRef ctx = LLVMContextCreate();
        final LLVMModuleRef mod = new LLVMModuleRef();
        final LLVMMemoryBufferRef buffer = LLVMCreateMemoryBufferWithMemoryRange(
                start, size, new BytePointer("partition" + partition), 0);
        try {
            if (LLVMParseBitcodeInContext2(ctx, buffer, mod) != 0) {
                throw new CompileException("Could not read the bitcode of partition " + partition);
            }
            final long t0 = System.nanoTime();
            keepPartition(mod, partition);
            final long t1 = System.nanoTime();

            final BytePointer err = new BytePointer();
            final LLVMTargetRef target = new LLVMTargetRef();
            if (LLVMGetTargetFromTriple(new BytePointer(triple), target, err) != 0) {
                throw new CompileException("Unknown target: " + err.getString());
            }
            final LLVMTargetMachineRef machine = LLVMCreateTargetMachine(
                    target, new BytePointer(triple),
                    new BytePointer("generic"), new BytePointer(""),
                    LLVMCodeGenLevelDefault, LLVMRelocPIC, LLVMCodeModelDefault);
            try {
                if (pipeline != null) {
                    NativeLLVMBackend.runPasses(mod, machine, pipeline);
                }
                final long t2 = System.nanoTime();
                if (LLVMTargetMachineEmitToFile(machine, mod,
                        new BytePointer(objectFile.toString()), LLVMObjectFile, err) != 0) {
                    throw new CompileException("Codegen failed (partition " + partition + "): "
                            + err.getString());
                }
                final long t3 = System.nanoTime();
                if (TIMING) {
                    System.err.println("[NativeLLVMBackend] partition " + partition + ": "
                            + countDefinedFunctions(mod) + " functions, split " + (t1 - t0) / 1_000_000L
                            + " ms, optimize " + (t2 - t1) / 1_000_000L + " ms, emit "
                            + (t3 - t2) / 1_000_000L + " ms");
                }
            } finally {
                LLVMDisposeTargetMachine(machine);
            }
        } finally {
            LLVMDisposeMemoryBuffer(buffer);
            if (!mod.isNull()) {
                LLVMDisposeModule(mod);
            }
            LLVMContextDispose(ctx);
        }
    }

    /** Turns everything another partition owns into declarations. */
    private void keepPartition(final LLVMModuleRef mod, final int partition) throws CompileException {
        final List<LLVMValueRef> foreignFunctions = new ArrayList<>();
        for (LLVMValueRef f = LLVMGetFirstFunction(mod); f != null && !f.isNull(); f = LLVMGetNextFunction(f)) {
            if (LLVMIsDeclaration(f) != 0) {
                continue;
            }
            final String name = name(f);
            if (functionOwnerByName.getOrDefault(name, 0) == partition) {
                continue;
            }
            final BitSet importedInto = imported.get(name);
            if (importedInto != null && importedInto.get(partition)) {
                LLVMSetLinkage(f, LLVMAvailableExternallyLinkage);
            } else {
                foreignFunctions.add(f);
            }
        }

        final List<LLVMValueRef> foreignLocals = new ArrayList<>();
        final List<LLVMValueRef> foreignGlobals = new ArrayList<>();
        for (LLVMValueRef g = LLVMGetFirstGlobal(mod); g != null && !g.isNull(); g = LLVMGetNextGlobal(g)) {
            if (LLVMIsDeclaration(g) != 0) {
                continue;
            }
            final String name = name(g);
            if (name.equals("llvm.global_ctors")) {
                if (partition != 0) {
                    foreignGlobals.add(g);
                }
                continue;
            }
            if (globalOwnerByName.getOrDefault(name, 0) == partition || duplicable.contains(name)) {
                continue;
            }
            foreignGlobals.add(g);
        }

        // First drop every foreign body and initializer, so that no foreign
        // definition still uses a local symbol of another partition.
        for (final LLVMValueRef f : foreignFunctions) {
            final LLVMValueRef declaration = toDeclaration(mod, f);
            if (isLocal(f)) {
                foreignLocals.add(declaration);
            }
            LLVMDeleteFunction(f);
        }
        for (final LLVMValueRef g : foreignGlobals) {
            LLVMSetInitializer(g, null);
            if (isLocal(g) || name(g).equals("llvm.global_ctors")) {
                foreignLocals.add(g);
            } else {
                LLVMSetLinkage(g, LLVMExternalLinkage);
            }
        }
        for (final LLVMValueRef local : foreignLocals) {
            final LLVMValueRef user = liveUser(local);
            if (user != null) {
                throw new CompileException("Partition " + partition + " still uses local symbol "
                        + name(local) + " of another partition (in " + describeUser(user) + ")");
            }
            // Only dead constants (old initializers) still refer to it.
            LLVMReplaceAllUsesWith(local, LLVMGetPoison(LLVMTypeOf(local)));
            if (LLVMIsAFunction(local) != null) {
                LLVMDeleteFunction(local);
            } else {
                LLVMDeleteGlobal(local);
            }
        }
    }

    /** Adds a declaration that replaces every use of {@code f}. */
    private static LLVMValueRef toDeclaration(final LLVMModuleRef mod, final LLVMValueRef f) {
        final String name = name(f);
        final int linkage = LLVMGetLinkage(f);
        final int visibility = LLVMGetVisibility(f);
        LLVMSetValueName2(f, "", 0);
        final LLVMValueRef declaration = LLVMAddFunction(mod, name, LLVMGlobalGetValueType(f));
        LLVMSetFunctionCallConv(declaration, LLVMGetFunctionCallConv(f));
        if (linkage != LLVMInternalLinkage && linkage != LLVMPrivateLinkage) {
            LLVMSetVisibility(declaration, visibility);
        }
        LLVMReplaceAllUsesWith(f, declaration);
        return declaration;
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    /**
     * A user of {@code value} that is part of the module: an instruction, a
     * global, or a constant such a user refers to. Constants that nothing
     * uses any more (an initializer that was replaced) do not count.
     */
    private static LLVMValueRef liveUser(final LLVMValueRef value) {
        for (LLVMUseRef u = LLVMGetFirstUse(value); u != null && !u.isNull(); u = LLVMGetNextUse(u)) {
            final LLVMValueRef user = LLVMGetUser(u);
            if (LLVMIsAInstruction(user) != null || LLVMIsAGlobalValue(user) != null) {
                return user;
            }
            final LLVMValueRef live = liveUser(user);
            if (live != null) {
                return live;
            }
        }
        return null;
    }

    private static String describeUser(final LLVMValueRef user) {
        if (LLVMIsAInstruction(user) != null) {
            return "function " + name(LLVMGetBasicBlockParent(LLVMGetInstructionParent(user)));
        }
        return "global " + name(user);
    }

    private static boolean isLocal(final LLVMValueRef global) {
        final int linkage = LLVMGetLinkage(global);
        return linkage == LLVMInternalLinkage || linkage == LLVMPrivateLinkage;
    }

    private static String name(final LLVMValueRef value) {
        final BytePointer name = LLVMGetValueName2(value, new SizeTPointer(1));
        return name == null || name.isNull() ? "" : name.getString();
    }
}

package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.Backend;
import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.util.CompileException;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.llvm.LLVM.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Native-code backend via LLVM (Bytedeco 21.1.8-1.5.13).
 *
 * Levert drie outputs:
 *   1. LLVM IR tekst (.ll)   — altijd gegenereerd voor inspectie
 *   2. Object file  (.o)     — native machinecode
 *   3. Executable            — gelinkt via gcc/clang/link.exe
 *
 * Platforms: Windows x64, Linux x64, macOS x64/ARM64
 *
 * Gebruik:
 *   NativeLLVMBackend backend = new NativeLLVMBackend();
 *   backend.compile(module, CompileOptions.debug(), Path.of("output.o"));
 */
public class NativeLLVMBackend implements Backend {

    private final io.github.potjerodekool.nabu.backend.nativeimage.ReflectionRegistry
            reflectionRegistry =
            new io.github.potjerodekool.nabu.backend.nativeimage.ReflectionRegistry();

    static {
        LLVMInitializeAllTargetInfos();
        LLVMInitializeAllTargets();
        LLVMInitializeAllTargetMCs();
        LLVMInitializeAllAsmParsers();
        LLVMInitializeAllAsmPrinters();
    }

    /**
     * Intel's JCC erratum: a jump that crosses or ends on a 32-byte boundary does not enter the decode cache, so a
     * hot loop can run 40% slower for no reason but where its code happens to land. This asks the assembler to pad
     * branches so that none does (+0.6% code). The assembler's relaxation is superlinear: for a large program (the
     * petstore, 35000 modules) code generation takes 100 times as long, so it is only on for a small one.
     * x86 only (an unknown option makes LLVM exit the process); -Dnabu.alignBranches=true|false overrides.
     */
    private static void alignBranches(final int moduleCount) {
        final List<String> options = new ArrayList<>();
        final String arch = System.getProperty("os.arch", "");
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            final String prop = System.getProperty("nabu.alignBranches");
            final boolean on = prop != null ? Boolean.parseBoolean(prop) : moduleCount <= 2000;
            options.add("-x86-branches-within-32B-boundaries=" + on);
        }
        // The profile of a PGO build: pgo-instr-use reads it from this option (the C API has no PGO options).
        final String profile = pgoProfile();
        if (profile != null) {
            options.add("-pgo-test-profile-file=" + profile);
        }
        if (options.isEmpty()) {
            return;
        }
        final org.bytedeco.javacpp.PointerPointer<BytePointer> argv =
                new org.bytedeco.javacpp.PointerPointer<>(options.size() + 1);
        argv.put(0, new BytePointer("nabu"));
        for (int i = 0; i < options.size(); i++) {
            argv.put(i + 1, new BytePointer(options.get(i)));
        }
        LLVMParseCommandLineOptions(options.size() + 1, argv, new BytePointer("nabu"));
    }

    /**
     * Profile-guided optimization, {@code -Dnabu.pgo=instrument} or {@code -Dnabu.pgo=<file>.profdata}.
     *
     * <p>{@code instrument}: every function counts its edges and the targets of its indirect calls (pgo-instr-gen,
     * then instrprof lowers the counters into the profile sections), and the program is linked with
     * {@code runtime/nabu_profile_rt.c}, which writes {@code LLVM_PROFILE_FILE} (default.profraw) when it ends, or every
     * {@code NABU_PROFILE_WRITE_EVERY} seconds. {@code llvm-profdata merge} (of the same LLVM major version as this
     * backend, 22) turns that into a .profdata.
     *
     * <p>A .profdata: the counts become branch weights and function entry counts (pgo-instr-use), and an indirect call
     * whose profile names a hot target becomes a check of the target with a direct, inlinable call
     * (pgo-icall-prom). The IR must be the IR that was instrumented (the same program, the same compiler); a function
     * whose shape changed is left without profile. Both run before the usual pipeline.
     */
    static boolean pgoInstrument() {
        return "instrument".equals(System.getProperty("nabu.pgo"));
    }

    private static String pgoProfile() {
        final String value = System.getProperty("nabu.pgo");
        return value != null && !value.isBlank() && !value.equals("instrument") ? value : null;
    }

    @Override
    public void compile(IRModule module,
                        CompileOptions opts,
                        Path outputObj) throws CompileException {
        compile(List.of(module), opts, outputObj);
    }

    @Override
    public boolean compilesLibraryBytecode() {
        return true;
    }

    @Override
    public void compileAll(List<IRModule> modules,
                           CompileOptions opts,
                           Path outputObj) throws CompileException {
        compile(modules, opts, outputObj);
    }

    /**
     * Compileert meerdere IRModules in één LLVM-batch. Dit is de enige manier
     * waarop super-velden, super type-info en cross-class override-slots kunnen
     * worden opgelost: de klassen delen één LLVM-context/module.
     *
     * De entry-brug ({@code main}) wordt gezocht over álle modules en blijft
     * achter op het object dat de {@code _main}-entry bevat.
     */
    public void compile(List<IRModule> modules,
                        CompileOptions opts,
                        Path outputObj) throws CompileException {
        final IRModule mainModule = ensureMainEntry(modules, opts.mainClass());
        loadNativeImageConfigs(outputObj, opts.classPath());

        // Triple zoveel mogelijk één keer bepalen: expliciet (opties) of
        // afgeleid van de gevonden toolchain (MinGW → gnu-triple), omdat
        // codegen én linken hetzelfde triple moeten gebruiken.
        String triple = opts.targetTriple() != null
                ? opts.targetTriple()
                : Linker.guessTargetTriple(LLVMGetDefaultTargetTriple().getString());

        // Named after the class with the main method (as GraalVM's
        // native-image does), not after whichever class came first.
        Path outFile = resolveOutputFile(mainModule != null ? mainModule : modules.getFirst(), outputObj,
                opts.imageName());
        // Alleen linken als er een main-functie aanwezig is (bepaald vóór compileToObject: dat geeft de
        // lijst vrij, zie hieronder).
        boolean hasMain = modules.stream()
                .flatMap(m -> m.functions().stream())
                .anyMatch(f -> f.name.equals("main") && !f.isExternal());
        List<Path> objectFiles = compileToObject(modules, opts, outFile, triple, true);
        if (!hasMain) return;

        Path exe = replaceExtension(outFile,
                triple.contains("windows") ? ".exe" : "");
        Linker.link(objectFiles, exe, triple, opts.gcStrategy(), opts.debugInfo());
        copyRuntimeData(exe);
    }

    /**
     * Data files of the JDK that the compiled JDK code reads at run time, from a {@code lib} directory
     * next to the executable (the runtime reports the executable's directory as {@code java.home}): the
     * time zone database. Without it {@code TimeZone}, {@code LocalDate.now()} and
     * {@code SimpleDateFormat} fail.
     */
    private void copyRuntimeData(final Path exe) {
        final Path source = Path.of(System.getProperty("java.home"), "lib", "tzdb.dat");
        if (!java.nio.file.Files.isRegularFile(source)) {
            return;
        }
        try {
            final Path lib = exe.toAbsolutePath().getParent().resolve("lib");
            java.nio.file.Files.createDirectories(lib);
            java.nio.file.Files.copy(source, lib.resolve("tzdb.dat"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.io.IOException e) {
            System.err.println("[NativeLLVMBackend] could not copy tzdb.dat: " + e.getMessage());
        }
    }

    private void loadNativeImageConfigs(final Path outputObj, final List<Path> classPath) {
        // De config kan aan twee kanten staan: in de class-output-root zelf
        // (javac/Filer schrijft META-INF/native-image in de output-map, bv.
        // target/classes) óf naast de .o in diens parent (zoals de e2e-tests).
        // Scan beide; outputObj is een map bij compileAll(targetDirectory).
        // Daarnaast de mappen en jars op het classpath: een library kan zijn
        // eigen configuratie meeleveren.
        final var abs = outputObj.toAbsolutePath();
        final var roots = new java.util.ArrayList<java.nio.file.Path>();
        if (java.nio.file.Files.isDirectory(abs)) {
            roots.add(abs);
        }
        if (abs.getParent() != null) {
            roots.add(abs.getParent());
        }
        roots.addAll(classPath);
        if (roots.isEmpty()) {
            return;
        }
        final var configs = io.github.potjerodekool.nabu.backend.nativeimage
                .NativeImageConfigScanner.scan(roots);
        for (final var config : configs) {
            reflectionRegistry.register(config);
        }
    }

    /** @return the module with the entry method, or null */
    private IRModule ensureMainEntry(List<IRModule> modules, String mainClass) {
        for (IRModule m : modules) {
            boolean hasEntry = m.functions().stream()
                    .anyMatch(f -> !f.isExternal() && f.name.endsWith("_main"));
            // with a main class given, only that class has the entry
            if (hasEntry && mainClass != null && !mainClass.replace('/', '.').equals(m.name.replace('/', '.'))) {
                continue;
            }
            if (hasEntry) {
                ensureMainEntry(m);
                return m;
            }
        }
        return null;
    }

    /**
     * Zorgt dat de module een letterlijke {@code main}-functie heeft. De
     * nabu-frontend emitteert de entry-methode van klasse {@code Main} als
     * {@code Main_main}. Als er nog geen letterlijke {@code main} is maar wel
     * zo'n entry-methode, synthetiseren we:
     *
     *   i32 @main(i32 %argc, i8* %argv) {
     *     %args = call i8* @nabu_args_from_argv(i32 %argc, i8* %argv)
     *     call void @<entry>(null, ..., %args)   // %args is de LAATSTE param
     *     ret i32 0
     *   }
     *
     * De echte argc/argv van het besturingssysteem worden via
     * {@code nabu_args_from_argv} (runtime/jcl_lite.c) omgezet naar een
     * java.lang.String[] en als LAATSTE argument aan de entry-methode
     * doorgegeven; eventuele parameters daarvóór (alleen 'this' in de
     * nabu-instance-vorm) blijven null. Zonder dit zou de entry-methode
     * nooit de echte commandline-argumenten zien: bv. een Java
     * {@code static void main(String[])} heeft één parameter (de args), een
     * nabu-instance {@code fun main(args: String[])} twee (this + args).
     */
    private void ensureMainEntry(IRModule module) {
        boolean hasMain = module.functions().stream()
                .anyMatch(f -> f.name.equals("main") && !f.isExternal());
        if (hasMain) return;

        IRFunction entry = module.functions().stream()
                .filter(f -> !f.isExternal() && f.name.endsWith("_main"))
                .findFirst()
                .orElse(null);
        if (entry == null) return;

        IRType.Ptr opaque = new IRType.Ptr(IRType.I8);

        // De native main() krijgt de ECHTE argc/argv van het besturingssysteem
        // en zet ze via de runtime-helper om naar een java.lang.String[] vóór
        // de entry-methode wordt aangeroepen (zie nabu_args_from_argv in
        // jcl_lite.c en de declaratie in LLVMModuleEmitter.declareRuntimeHelpers).
        IRValue.Temp argcParam = new IRValue.Temp("argc", IRType.I32);
        IRValue.Temp argvParam = new IRValue.Temp("argv", opaque);
        IRValue.Temp argsArray = new IRValue.Temp("main.args", opaque);

        List<IRValue> args = new java.util.ArrayList<>(entry.params.size());
        for (int i = 0; i < entry.params.size(); i++) {
            // De LAATSTE parameter is de args-array; alles daarvóór (enkel
            // 'this' in de instance-vorm) blijft null, zoals voorheen.
            args.add(i == entry.params.size() - 1
                    ? argsArray
                    : new IRValue.ConstNull(opaque));
        }
        List<IRType> paramTypes = entry.params.stream()
                .map(IRValue::type)
                .toList();

        IRFunction bridge = new IRFunction("main", IRType.I32,
                List.of(argcParam, argvParam), SourceLocation.UNKNOWN, 0);
        IRBasicBlock entryBlock = new IRBasicBlock("entry");
        if (!entry.params.isEmpty()) {
            entryBlock.add(new IRInstruction.Call(CallKind.STATIC, opaque,
                    List.of(IRType.I32, opaque), argsArray, "nabu_args_from_argv",
                    List.of(argcParam, argvParam), SourceLocation.UNKNOWN));
        }
        if (IRType.I32.equals(entry.returnType)) {
            // main(): int → geef het resultaat door als exit-code (handig voor
            // runnable e2e-tests zonder exception-handling).
            IRValue.Temp result = new IRValue.Temp("main.result", IRType.I32);
            entryBlock.add(new IRInstruction.Call(CallKind.STATIC, IRType.I32,
                    paramTypes, result, entry.name, args, SourceLocation.UNKNOWN));
            entryBlock.add(mainReturned());
            entryBlock.add(new IRInstruction.Return(result, SourceLocation.UNKNOWN));
        } else {
            entryBlock.add(new IRInstruction.Call(CallKind.STATIC, IRType.VOID,
                    paramTypes, null, entry.name, args, SourceLocation.UNKNOWN));
            entryBlock.add(mainReturned());
            entryBlock.add(new IRInstruction.Return(IRValue.ofI32(0), SourceLocation.UNKNOWN));
        }
        bridge.addBlock(entryBlock);
        module.addFunction(bridge);
    }

    /**
     * The call of {@code nabu_main_returned} after the entry method: like the JVM, the process waits there for the
     * threads that are not daemons. That happens before {@code exit}, not in an exit handler: the C library holds its
     * exit lock while the handlers run, and a thread that then calls {@code exit} (System.exit) or {@code atexit}
     * (the first double formatted by printf does) would wait for it forever.
     */
    private static IRInstruction mainReturned() {
        return new IRInstruction.Call(CallKind.STATIC, IRType.VOID, List.of(), null, "nabu_main_returned",
                List.of(), SourceLocation.UNKNOWN);
    }

    /**
     * Compileert naar object file zonder te linken.
     * Handig voor tests en bibliotheken.
     */
    public void compileToObject(IRModule module,
                                CompileOptions opts,
                                Path outputObj) throws CompileException {
        compileToObject(List.of(module), opts, outputObj);
    }

    /**
     * Compileert meerdere modules naar een object file zonder te linken.
     * Handig voor tests en bibliotheken.
     */
    public void compileToObject(List<IRModule> modules,
                                CompileOptions opts,
                                Path outputObj) throws CompileException {
        String triple = opts.targetTriple() != null
                ? opts.targetTriple()
                : LLVMGetDefaultTargetTriple().getString();
        compileToObject(modules, opts, outputObj, triple, false);
    }

    /**
     * Laat bridge-methoden weg die alleen in returntype van hun doel
     * verschillen (bv. {@code Object convert(String)} naast
     * {@code String convert(String)}): de JVM heeft ze nodig, maar hier
     * delen ze het LLVM-symbool van het doel, en de vtable/itable koppelt al
     * op naam en parameters. (Zelfde keuze als de bytecode-lowering.)
     */
    static IRModule withoutReturnOnlyBridges(final IRModule module) {
        final List<IRFunction> functions = module.functions();
        final List<IRFunction> kept = functions.stream()
                .filter(fn -> !isReturnOnlyBridge(fn, functions))
                .toList();
        return kept.size() == functions.size() ? module : module.withFunctions(kept);
    }

    private static boolean isReturnOnlyBridge(final IRFunction fn, final List<IRFunction> functions) {
        if (!io.github.potjerodekool.nabu.lang.Flags.hasFlag(fn.getFlags(),
                io.github.potjerodekool.nabu.lang.Flags.BRIDGE)) {
            return false;
        }
        final var paramTypes = fn.params.stream().map(IRValue::type).toList();
        return functions.stream().anyMatch(other -> other != fn
                && other.name.equals(fn.name)
                && !io.github.potjerodekool.nabu.lang.Flags.hasFlag(other.getFlags(),
                        io.github.potjerodekool.nabu.lang.Flags.BRIDGE)
                && other.params.stream().map(IRValue::type).toList().equals(paramTypes));
    }

    /**
     * The object files of a build in {@code partitions} parts: {@code X.o}
     * for partition 0, {@code X.1.o}, {@code X.2.o}, … for the others. Parts
     * left over from an earlier build with more partitions are removed.
     */
    private static List<Path> objectFiles(Path outputObj, int partitions) {
        final List<Path> files = new java.util.ArrayList<>();
        files.add(outputObj);
        for (int p = 1; p < partitions; p++) {
            files.add(replaceExtension(outputObj, "." + p + ".o"));
        }
        for (int p = Math.max(1, partitions); ; p++) {
            final Path stale = replaceExtension(outputObj, "." + p + ".o");
            try {
                if (!Files.deleteIfExists(stale)) {
                    break;
                }
            } catch (final java.io.IOException e) {
                break;
            }
        }
        return files;
    }

    /**
     * @param partitioned whether a large module may be optimized and
     *                    compiled in parallel partitions (several object files)
     * @return the object files written
     */
    private List<Path> compileToObject(List<IRModule> modules,
                                       CompileOptions opts,
                                       Path outputObj,
                                       String triple,
                                       boolean partitioned) throws CompileException {
        BuildDiagnostics.reset();
        alignBranches(modules.size());
        LLVMContextRef ctx     = LLVMContextCreate();
        // The names of instructions, arguments and blocks are only for reading the .ll: for a large program they
        // are gigabytes (a string per value, 200000 functions). -Dnabu.llvm.discardNames=true|false overrides.
        final String discard = System.getProperty("nabu.llvm.discardNames");
        if (discard != null ? Boolean.parseBoolean(discard) : partitioned && modules.size() > 2000) {
            LLVMContextSetDiscardValueNames(ctx, 1);
        }
        LLVMModuleRef  llvmMod = LLVMModuleCreateWithNameInContext(modules.getFirst().name, ctx);
        LLVMBuilderRef builder = LLVMCreateBuilderInContext(ctx);
        final boolean[] moduleDisposed = {false};
        final boolean[] builderDisposed = {false};
        final boolean[] contextDisposed = {false};

        // No PointerScope here: it would hold every JavaCPP object made while emitting (a value reference, a name, a
        // deallocator each: tens of millions for a large program, gigabytes) until the end of the method, also during
        // the code generation. The garbage collector frees the few that own native memory.
        try {
            long t0 = System.nanoTime();
            final int moduleCount = modules.size();
            LLVMModuleEmitter emitter = new LLVMModuleEmitter(ctx, llvmMod, builder, opts);
            emitter.setReflectionRegistry(reflectionRegistry);
            // Without optimization the code of a large program is generated while the bodies are emitted, in chunks:
            // the bodies of the whole program are never in memory at once. -Dnabu.llvm.chunkInstructions=<n> sets the
            // size of a chunk in IR instructions (0 = off).
            final boolean singleModule = opts.optLevel() == CompileOptions.OptLevel.NONE
                    && System.getProperty("nabu.llvm.partitions") == null;
            final long chunkSize = Long.getLong("nabu.llvm.chunkInstructions",
                    partitioned && singleModule && modules.size() > 2000 ? 600_000L : 0L);
            ChunkedCodegen chunker = null;
            LLVMTargetMachineRef chunkMachine = null;
            if (chunkSize > 0) {
                final BytePointer targetErr = new BytePointer();
                final LLVMTargetRef target = new LLVMTargetRef();
                if (LLVMGetTargetFromTriple(new BytePointer(triple), target, targetErr) != 0)
                    throw new CompileException("Unknown target: " + targetErr.getString());
                chunkMachine = LLVMCreateTargetMachine(target, new BytePointer(triple),
                        new BytePointer("generic"), new BytePointer(""), LLVMCodeGenLevelNone, LLVMRelocPIC,
                        LLVMCodeModelDefault);
                objectFiles(outputObj, 1);
                chunker = new ChunkedCodegen(llvmMod, chunkMachine, triple, outputObj);
                emitter.setChunker(chunker, chunkSize);
            }
            for (final IRModule m : modules) {
                if (m.functions().stream().anyMatch(f -> f.name.equals("main") && !f.isExternal())) {
                    emitter.setMainClassName(m.name);
                    break;
                }
            }
            emitter.emitAll(modules.stream().map(NativeLLVMBackend::withoutReturnOnlyBridges).toList());
            final java.util.List<String> unresolved = emitter.stubUnresolvedSymbols();
            if (!unresolved.isEmpty()) {
                // Grouped by class: a handful of classes (Unsafe, the file
                // system, ...) usually account for most of the list.
                final java.util.Map<String, java.util.List<String>> byClass = new java.util.TreeMap<>();
                for (final String name : unresolved) {
                    final int sep = name.lastIndexOf('_');
                    byClass.computeIfAbsent(sep > 0 ? name.substring(0, sep) : name,
                            k -> new java.util.ArrayList<>()).add(name);
                }
                final Path unresolvedPath = replaceExtension(outputObj, ".unresolved.txt");
                final java.util.List<String> lines = new java.util.ArrayList<>();
                byClass.forEach((owner, names) -> {
                    lines.add("# " + owner + " (" + names.size() + ")");
                    lines.addAll(names);
                });
                try {
                    java.nio.file.Files.write(unresolvedPath, lines);
                } catch (final java.io.IOException e) {
                    System.err.println("[NativeLLVMBackend] could not write " + unresolvedPath
                            + ": " + e.getMessage());
                }
                System.err.println("[NativeLLVMBackend] warning: " + unresolved.size()
                        + " called JDK functions in " + byClass.size() + " classes have no"
                        + " implementation (native, signature-polymorphic or not found) and abort"
                        + " the program when they are executed. List by class: " + unresolvedPath);
            }
            BuildDiagnostics.report(replaceExtension(outputObj, ".warnings.txt"));
            // The emitter's maps (values, layouts, strings) are only needed up to here.
            emitter = null;
            if (partitioned) {
                // The LLVM module holds the program now. The caller's list keeps the Java IR of all of it alive
                // during the code generation: let go of it (a program of 36000 classes is gigabytes).
                try {
                    modules.clear();
                } catch (final UnsupportedOperationException e) {
                    // an immutable list: the caller keeps its IR
                }
            }
            if (partitioned) {
                // The Java heap is garbage now (IR, emitter maps) but nothing makes the collector run, so the
                // process keeps gigabytes of it during the code generation: a full collection returns them.
                System.gc();
            }
            long t1 = System.nanoTime();
            System.err.println("[NativeLLVMBackend] emitAll (" + moduleCount + " IR-modules -> LLVM-IR): "
                    + (t1 - t0) / 1_000_000L + " ms");

            if (chunker != null) {
                System.gc();
                long tc = System.nanoTime();
                chunker.finish();
                System.err.println("[NativeLLVMBackend] last object file of " + chunker.objects().size() + ": "
                        + (System.nanoTime() - tc) / 1_000_000L + " ms");
                LLVMDisposeTargetMachine(chunkMachine);
                return chunker.objects();
            }
            LLVMSetTarget(llvmMod, new BytePointer(triple));

            BytePointer   err       = new BytePointer();
            LLVMTargetRef targetRef = new LLVMTargetRef();
            if (LLVMGetTargetFromTriple(new BytePointer(triple), targetRef, err) != 0)
                throw new CompileException("Unknown target: " + err.getString());

            LLVMTargetMachineRef machine = LLVMCreateTargetMachine(
                    targetRef, new BytePointer(triple),
                    new BytePointer("generic"), new BytePointer(""),
                    opts.optLevel() == CompileOptions.OptLevel.NONE ? LLVMCodeGenLevelNone : LLVMCodeGenLevelDefault,
                    LLVMRelocPIC, LLVMCodeModelDefault);

            LLVMSetModuleDataLayout(llvmMod, LLVMCreateTargetDataLayout(machine));

            // .ll eerst schrijven: ook als validatie faalt is de IR
            // beschikbaar voor inspectie.
            Path llPath = replaceExtension(outputObj, ".ll");
            long t2 = System.nanoTime();
            // A large program has no value names (see above): its .ll is gigabytes nobody reads, and writing it takes
            // half a minute. -Dnabu.llvm.writeLl=true|false overrides.
            final String writeLl = System.getProperty("nabu.llvm.writeLl");
            if (writeLl != null ? Boolean.parseBoolean(writeLl) : !(partitioned && moduleCount > 2000)) {
                emitLLVMIR(llvmMod, llPath);
            }
            long t3 = System.nanoTime();
            System.err.println("[NativeLLVMBackend] writing .ll: " + (t3 - t2) / 1_000_000L + " ms");

            validate(llvmMod);
            long t4 = System.nanoTime();
            System.err.println("[NativeLLVMBackend] validate: " + (t4 - t3) / 1_000_000L + " ms");

            // Without optimization the code generation is the only work: one module is faster than the split into
            // partitions (writing, parsing and trimming the bitcode of the whole program), and needs less memory.
            final boolean single = opts.optLevel() == CompileOptions.OptLevel.NONE
                    && System.getProperty("nabu.llvm.partitions") == null;
            final int partitions = partitioned && !single
                    ? PartitionedCodegen.partitionCount(PartitionedCodegen.countDefinedFunctions(llvmMod))
                    : 1;
            final List<Path> objectFiles = objectFiles(outputObj, partitions);
            if (partitions > 1) {
                LLVMDisposeTargetMachine(machine);
                PartitionedCodegen.compile(llvmMod, partitions, triple,
                        opts.optLevel() != CompileOptions.OptLevel.NONE ? effectivePipeline(opts.optLevel()) : null,
                        objectFiles, () -> {
                            // The context too: it keeps the types, constants and metadata of the whole program
                            // alive, also after the module is gone.
                            LLVMDisposeBuilder(builder);
                            builderDisposed[0] = true;
                            LLVMDisposeModule(llvmMod);
                            moduleDisposed[0] = true;
                            LLVMContextDispose(ctx);
                            contextDisposed[0] = true;
                        });
                long t5 = System.nanoTime();
                System.err.println("[NativeLLVMBackend] optimize (" + opts.optLevel() + ") + emitObjectFile, "
                        + partitions + " partitions in parallel: " + (t5 - t4) / 1_000_000L + " ms");
                return objectFiles;
            }

            if (opts.optLevel() != CompileOptions.OptLevel.NONE) {
                optimize(llvmMod, machine, opts.optLevel());
            }
            long t5 = System.nanoTime();
            System.err.println("[NativeLLVMBackend] optimize (" + opts.optLevel() + "): "
                    + (t5 - t4) / 1_000_000L + " ms");

            emitObjectFile(llvmMod, machine, outputObj, err);
            long t6 = System.nanoTime();
            System.err.println("[NativeLLVMBackend] emitObjectFile: " + (t6 - t5) / 1_000_000L + " ms");
            LLVMDisposeTargetMachine(machine);
            return objectFiles;

        } finally {
            if (!builderDisposed[0]) {
                LLVMDisposeBuilder(builder);
            }
            if (!moduleDisposed[0]) {
                LLVMDisposeModule(llvmMod);
            }
            if (!contextDisposed[0]) {
                LLVMContextDispose(ctx);
            }
        }
    }

    /**
     * Emitteert uitsluitend de LLVM IR tekst (.ll) van een module, zonder
     * codegen of object-emissie.
     *
     * Wordt gebruikt voor inspectie en voor tests die de frontend
     * → IR → LLVM-pipeline willen verifiëren. De module wordt wél volledig
     * gevalideerd, zodat de IR-asserties in die tests ook echt op geldige IR
     * gebaseerd zijn; alleen de doeltaalmachine/codegen wordt overgeslagen.
     */
    public void emitIRText(IRModule module,
                           CompileOptions opts,
                           Path llPath) throws CompileException {
        LLVMContextRef ctx     = LLVMContextCreate();
        LLVMModuleRef  llvmMod = LLVMModuleCreateWithNameInContext(module.name, ctx);
        LLVMBuilderRef builder = LLVMCreateBuilderInContext(ctx);

        try (PointerScope scope = new PointerScope()) {
            var emitter = new LLVMModuleEmitter(ctx, llvmMod, builder, opts);
            emitter.emit(module);

            String triple = opts.targetTriple() != null
                    ? opts.targetTriple()
                    : LLVMGetDefaultTargetTriple().getString();
            LLVMSetTarget(llvmMod, new BytePointer(triple));

            validate(llvmMod);

            emitLLVMIR(llvmMod, llPath);
        } finally {
            LLVMDisposeBuilder(builder);
            LLVMDisposeModule(llvmMod);
            LLVMContextDispose(ctx);
        }
    }

    // -------------------------------------------------------
    // Validatie
    // -------------------------------------------------------

    private void validate(LLVMModuleRef mod) throws CompileException {
        BytePointer err = new BytePointer();
        if (LLVMVerifyModule(mod, LLVMReturnStatusAction, err) != 0) {
            String msg = err.getString();
            LLVMDisposeMessage(err);
            throw new CompileException("LLVM validatiefout:\n" + msg);
        }
    }

    // -------------------------------------------------------
    // LLVM IR tekst
    // -------------------------------------------------------

    private void emitLLVMIR(LLVMModuleRef mod, Path path) throws CompileException {
        BytePointer err = new BytePointer();
        if (LLVMPrintModuleToFile(mod, new BytePointer(path.toString()), err) != 0) {
            String msg = err.getString();
            LLVMDisposeMessage(err);
            throw new CompileException("Could not write .ll: " + msg);
        }
    }

    // -------------------------------------------------------
    // Optimalisatie (nieuwe PassManager API — LLVM 17+)
    // -------------------------------------------------------

    private void optimize(LLVMModuleRef mod,
                          LLVMTargetMachineRef machine,
                          CompileOptions.OptLevel level) throws CompileException {
        runPasses(mod, machine, effectivePipeline(level));
    }

    /** Returns the PGO-override pipeline if set, otherwise the default pipeline for {@code level}. */
    private static String effectivePipeline(CompileOptions.OptLevel level) {
        // PGO override: -Dnabu.pgo.pipeline=<pipeline string>
        // Examples:
        //   instrument: pgo-instr-gen,default<O2>
        //   use:        pgo-instr-use</abs/path/to/profile.profdata>,default<O2>
        String pgoPipeline = System.getProperty("nabu.pgo.pipeline");
        if (pgoPipeline != null && !pgoPipeline.isBlank()) {
            return pgoPipeline;
        }
        if (pgoInstrument()) {
            return "pgo-instr-gen,instrprof," + pipeline(level);
        }
        if (pgoProfile() != null) {
            return "pgo-instr-use,pgo-icall-prom," + pipeline(level);
        }
        return pipeline(level);
    }

    private static String pipeline(CompileOptions.OptLevel level) {
        return switch (level) {
            case NONE             -> "mem2reg";
            case DEFAULT          -> "default<O2>";
            case AGGRESSIVE       -> "default<O3>";
            case SIZE             -> "default<Os>";
            case SIZE_AGGRESSIVE  -> "default<Oz>";
        };
    }

    /** Whether this build uses a PGO profile (-Dnabu.pgo=&lt;profdata&gt;), so that the functions have entry counts. */
    static boolean pgoUse() {
        return pgoProfile() != null;
    }

    /**
     * With a PGO profile, the functions the training run never entered (entry count 0) go to a section of their own
     * ({@code -Dnabu.pgo.cold=false}: off): the linker puts them together, so the pages of the code that a server
     * uses are not shared with the cold code and the cold pages are never read into memory. Returns how many.
     */
    static int placeColdFunctions(final LLVMContextRef ctx, final LLVMModuleRef mod, final String triple) {
        if (!"false".equalsIgnoreCase(System.getProperty("nabu.pgo.cold", "true")) && pgoUse()) {
            final String t = triple.toLowerCase(java.util.Locale.ROOT);
            final String section = t.contains("apple") || t.contains("darwin") ? "__TEXT,__nabu_cold"
                    : t.contains("windows") || t.contains("mingw") ? ".text$zcold"
                    : ".text.unlikely.nabu";
            final BytePointer kindName = new BytePointer("prof");
            final int kind = LLVMGetMDKindIDInContext(ctx, kindName, 4);
            int cold = 0;
            for (LLVMValueRef f = LLVMGetFirstFunction(mod); f != null && !f.isNull(); f = LLVMGetNextFunction(f)) {
                if (LLVMIsDeclaration(f) != 0) {
                    continue;
                }
                final LLVMValueRef node = LLVMGetMetadata(f, kind);
                if (node == null || node.isNull() || LLVMGetMDNodeNumOperands(node) < 2) {
                    continue;
                }
                final int n = LLVMGetMDNodeNumOperands(node);
                final org.bytedeco.javacpp.PointerPointer<LLVMValueRef> ops = new org.bytedeco.javacpp.PointerPointer<>(n);
                LLVMGetMDNodeOperands(node, ops);
                final org.bytedeco.javacpp.IntPointer length = new org.bytedeco.javacpp.IntPointer(1);
                final BytePointer label = LLVMGetMDString(ops.get(LLVMValueRef.class, 0), length);
                if (label != null && !label.isNull() && "function_entry_count".equals(label.getString())
                        && LLVMConstIntGetZExtValue(ops.get(LLVMValueRef.class, 1)) == 0
                        && hasNoSection(f)) {
                    LLVMSetSection(f, section);
                    cold++;
                }
            }
            return cold;
        }
        return 0;
    }

    private static boolean hasNoSection(final LLVMValueRef function) {
        final BytePointer section = LLVMGetSection(function);
        return section == null || section.isNull() || section.getString().isEmpty();
    }

    static void runPasses(LLVMModuleRef mod,
                          LLVMTargetMachineRef machine,
                          String pipeline) throws CompileException {
        LLVMPassBuilderOptionsRef options = LLVMCreatePassBuilderOptions();
        LLVMPassBuilderOptionsSetLoopUnrolling(options, 1);

        LLVMErrorRef error = LLVMRunPasses(mod,
                new BytePointer(pipeline), machine, options);

        if (error != null && !error.isNull()) {
            BytePointer msg = LLVMGetErrorMessage(error);
            String text = msg.getString();
            LLVMDisposeErrorMessage(msg);
            LLVMDisposePassBuilderOptions(options);
            throw new CompileException("Optimization failed: " + text);
        }
        LLVMDisposePassBuilderOptions(options);
    }

    // -------------------------------------------------------
    // Object file
    // -------------------------------------------------------

    private void emitObjectFile(LLVMModuleRef mod,
                                LLVMTargetMachineRef machine,
                                Path output,
                                BytePointer err) throws CompileException {
        if (LLVMTargetMachineEmitToFile(machine, mod,
                new BytePointer(output.toString()), LLVMObjectFile, err) != 0)
            throw new CompileException("Codegen failed: " + err.getString());
    }

    // -------------------------------------------------------
    // Hulp
    // -------------------------------------------------------

    /**
     * Normaliseert het output-pad. Backends ontvangen een output-directory
     * (net als de ASM-backend); hier wordt daar een object-bestand in
     * aangemaakt op basis van de module-naam. Een expliciet bestandspad
     * (zoals in tests) wordt ongewijzigd gebruikt.
     */
    private static Path resolveOutputFile(IRModule module, Path output, String imageName) {
        final String baseName = imageName != null ? imageName : module.name;
        if (output == null) {
            return Path.of(baseName + ".o");
        }
        if (Files.isDirectory(output)) {
            return output.resolve(baseName + ".o");
        }
        if (Files.exists(output)) {
            return output;
        }
        // Bestaat het pad nog niet: als het eindigt op een directory-separator
        // of geen extensie heeft en de module een naam heeft, dan een directory.
        final var name = output.getFileName() != null
                ? output.getFileName().toString() : "";
        if (name.isEmpty() || name.indexOf('.') < 0) {
            return output.resolve(baseName + ".o");
        }
        return output;
    }

    private static Path replaceExtension(Path path, String newExt) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        return path.getParent() != null
                ? path.getParent().resolve(base + newExt)
                : Path.of(base + newExt);
    }
}

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

    private final io.github.potjerodekool.nabu.compiler.backend.native_llvm.config.ReflectionRegistry
            reflectionRegistry =
            new io.github.potjerodekool.nabu.compiler.backend.native_llvm.config.ReflectionRegistry();

    static {
        LLVMInitializeAllTargetInfos();
        LLVMInitializeAllTargets();
        LLVMInitializeAllTargetMCs();
        LLVMInitializeAllAsmParsers();
        LLVMInitializeAllAsmPrinters();
    }

    @Override
    public void compile(IRModule module,
                        CompileOptions opts,
                        Path outputObj) throws CompileException {
        compile(List.of(module), opts, outputObj);
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
        ensureMainEntry(modules);
        loadNativeImageConfigs(outputObj);

        // Triple zoveel mogelijk één keer bepalen: expliciet (opties) of
        // afgeleid van de gevonden toolchain (MinGW → gnu-triple), omdat
        // codegen én linken hetzelfde triple moeten gebruiken.
        String triple = opts.targetTriple() != null
                ? opts.targetTriple()
                : Linker.guessTargetTriple(LLVMGetDefaultTargetTriple().getString());

        Path outFile = resolveOutputFile(modules.getFirst(), outputObj);
        compileToObject(modules, opts, outFile, triple);

        // Alleen linken als er een main-functie aanwezig is
        boolean hasMain = modules.stream()
                .flatMap(m -> m.functions().stream())
                .anyMatch(f -> f.name.equals("main") && !f.isExternal());
        if (!hasMain) return;

        Path exe = replaceExtension(outFile,
                triple.contains("windows") ? ".exe" : "");
        Linker.link(outFile, exe, triple, opts.gcStrategy());
    }

    private void loadNativeImageConfigs(final Path outputObj) {
        // De config kan aan twee kanten staan: in de class-output-root zelf
        // (javac/Filer schrijft META-INF/native-image in de output-map, bv.
        // target/classes) óf naast de .o in diens parent (zoals de e2e-tests).
        // Scan beide; outputObj is een map bij compileAll(targetDirectory).
        final var abs = outputObj.toAbsolutePath();
        final var roots = new java.util.ArrayList<java.nio.file.Path>();
        if (java.nio.file.Files.isDirectory(abs)) {
            roots.add(abs);
        }
        if (abs.getParent() != null) {
            roots.add(abs.getParent());
        }
        if (roots.isEmpty()) {
            return;
        }
        final var configs = io.github.potjerodekool.nabu.compiler.backend.native_llvm.config
                .NativeImageConfigScanner.scan(roots);
        for (final var config : configs) {
            reflectionRegistry.register(config);
        }
    }

    private void ensureMainEntry(List<IRModule> modules) {
        for (IRModule m : modules) {
            boolean hasEntry = m.functions().stream()
                    .anyMatch(f -> !f.isExternal() && f.name.endsWith("_main"));
            if (hasEntry) {
                ensureMainEntry(m);
                return;
            }
        }
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
            entryBlock.add(new IRInstruction.Return(result, SourceLocation.UNKNOWN));
        } else {
            entryBlock.add(new IRInstruction.Call(CallKind.STATIC, IRType.VOID,
                    paramTypes, null, entry.name, args, SourceLocation.UNKNOWN));
            entryBlock.add(new IRInstruction.Return(IRValue.ofI32(0), SourceLocation.UNKNOWN));
        }
        bridge.addBlock(entryBlock);
        module.addFunction(bridge);
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
        compileToObject(modules, opts, outputObj, triple);
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

    private void compileToObject(List<IRModule> modules,
                                 CompileOptions opts,
                                 Path outputObj,
                                 String triple) throws CompileException {
        LLVMContextRef ctx     = LLVMContextCreate();
        LLVMModuleRef  llvmMod = LLVMModuleCreateWithNameInContext(modules.getFirst().name, ctx);
        LLVMBuilderRef builder = LLVMCreateBuilderInContext(ctx);

        try (PointerScope scope = new PointerScope()) {
            long t0 = System.nanoTime();
            var emitter = new LLVMModuleEmitter(ctx, llvmMod, builder, opts);
            emitter.setReflectionRegistry(reflectionRegistry);
            emitter.emitAll(modules.stream().map(NativeLLVMBackend::withoutReturnOnlyBridges).toList());
            final java.util.List<String> unresolved = emitter.stubUnresolvedSymbols();
            if (!unresolved.isEmpty()) {
                final Path unresolvedPath = replaceExtension(outputObj, ".unresolved.txt");
                try {
                    java.nio.file.Files.write(unresolvedPath, unresolved);
                } catch (final java.io.IOException e) {
                    System.err.println("[NativeLLVMBackend] kon " + unresolvedPath
                            + " niet schrijven: " + e.getMessage());
                }
                System.err.println("[NativeLLVMBackend] waarschuwing: " + unresolved.size()
                        + " aangeroepen JDK-functies hebben geen implementatie (native,"
                        + " signature-polymorf of niet gevonden) en breken het programma af"
                        + " als ze worden uitgevoerd. Lijst: " + unresolvedPath);
            }
            long t1 = System.nanoTime();
            System.err.println("[NativeLLVMBackend] emitAll (" + modules.size() + " IR-modules -> LLVM-IR): "
                    + (t1 - t0) / 1_000_000L + " ms");

            LLVMSetTarget(llvmMod, new BytePointer(triple));

            BytePointer   err       = new BytePointer();
            LLVMTargetRef targetRef = new LLVMTargetRef();
            if (LLVMGetTargetFromTriple(new BytePointer(triple), targetRef, err) != 0)
                throw new CompileException("Onbekend target: " + err.getString());

            LLVMTargetMachineRef machine = LLVMCreateTargetMachine(
                    targetRef, new BytePointer(triple),
                    new BytePointer("generic"), new BytePointer(""),
                    LLVMCodeGenLevelDefault, LLVMRelocPIC, LLVMCodeModelDefault);

            LLVMSetModuleDataLayout(llvmMod, LLVMCreateTargetDataLayout(machine));

            // .ll eerst schrijven: ook als validatie faalt is de IR
            // beschikbaar voor inspectie.
            Path llPath = replaceExtension(outputObj, ".ll");
            long t2 = System.nanoTime();
            emitLLVMIR(llvmMod, llPath);
            long t3 = System.nanoTime();
            System.err.println("[NativeLLVMBackend] .ll wegschrijven: " + (t3 - t2) / 1_000_000L + " ms");

            validate(llvmMod);
            long t4 = System.nanoTime();
            System.err.println("[NativeLLVMBackend] validate: " + (t4 - t3) / 1_000_000L + " ms");

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

        } finally {
            LLVMDisposeBuilder(builder);
            LLVMDisposeModule(llvmMod);
            LLVMContextDispose(ctx);
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
            throw new CompileException("Kon .ll niet schrijven: " + msg);
        }
    }

    // -------------------------------------------------------
    // Optimalisatie (nieuwe PassManager API — LLVM 17+)
    // -------------------------------------------------------

    private void optimize(LLVMModuleRef mod,
                          LLVMTargetMachineRef machine,
                          CompileOptions.OptLevel level) throws CompileException {
        String pipeline = switch (level) {
            case NONE       -> "mem2reg";
            case DEFAULT    -> "default<O2>";
            case AGGRESSIVE -> "default<O3>";
        };

        LLVMPassBuilderOptionsRef options = LLVMCreatePassBuilderOptions();
        LLVMPassBuilderOptionsSetLoopUnrolling(options, 1);

        LLVMErrorRef error = LLVMRunPasses(mod,
                new BytePointer(pipeline), machine, options);

        if (error != null && !error.isNull()) {
            BytePointer msg = LLVMGetErrorMessage(error);
            String text = msg.getString();
            LLVMDisposeErrorMessage(msg);
            LLVMDisposePassBuilderOptions(options);
            throw new CompileException("Optimalisatie mislukt: " + text);
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
            throw new CompileException("Codegen mislukt: " + err.getString());
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
    private static Path resolveOutputFile(IRModule module, Path output) {
        if (output == null) {
            return Path.of(module.name + ".o");
        }
        if (Files.isDirectory(output)) {
            return output.resolve(module.name + ".o");
        }
        if (Files.exists(output)) {
            return output;
        }
        // Bestaat het pad nog niet: als het eindigt op een directory-separator
        // of geen extensie heeft en de module een naam heeft, dan een directory.
        final var name = output.getFileName() != null
                ? output.getFileName().toString() : "";
        if (name.isEmpty() || name.indexOf('.') < 0) {
            return output.resolve(module.name + ".o");
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

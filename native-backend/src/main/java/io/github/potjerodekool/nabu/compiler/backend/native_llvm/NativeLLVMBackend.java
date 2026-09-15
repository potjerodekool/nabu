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
        final var classPathRoot = outputObj.toAbsolutePath().getParent();
        if (classPathRoot == null) {
            return;
        }
        final var configs = io.github.potjerodekool.nabu.compiler.backend.native_llvm.config
                .NativeImageConfigScanner.scan(java.util.List.of(classPathRoot));
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
     *   i32 @main() {
     *     call void @<entry>(null, null, ...)   // per parameter
     *     ret i32 0
     *   }
     *
     * Het aantal nulls volgt de parameterlijst van de entry: bv. een Java
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

        List<IRValue> args = new java.util.ArrayList<>(entry.params.size());
        for (int i = 0; i < entry.params.size(); i++) {
            args.add(new IRValue.ConstNull(opaque));
        }
        List<IRType> paramTypes = entry.params.stream()
                .map(IRValue::type)
                .toList();

        IRFunction bridge = new IRFunction("main", IRType.I32,
                List.of(), SourceLocation.UNKNOWN, 0);
        IRBasicBlock entryBlock = new IRBasicBlock("entry");
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

    private void compileToObject(List<IRModule> modules,
                                 CompileOptions opts,
                                 Path outputObj,
                                 String triple) throws CompileException {
        LLVMContextRef ctx     = LLVMContextCreate();
        LLVMModuleRef  llvmMod = LLVMModuleCreateWithNameInContext(modules.getFirst().name, ctx);
        LLVMBuilderRef builder = LLVMCreateBuilderInContext(ctx);

        try {
            var emitter = new LLVMModuleEmitter(ctx, llvmMod, builder, opts);
            emitter.emitAll(modules);

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
            emitLLVMIR(llvmMod, llPath);

            validate(llvmMod);

            if (opts.optLevel() != CompileOptions.OptLevel.NONE)
                optimize(llvmMod, machine, opts.optLevel());

            emitObjectFile(llvmMod, machine, outputObj, err);
            LLVMDisposeTargetMachine(machine);

        } finally {
            LLVMDisposeBuilder(builder);
            LLVMDisposeModule(llvmMod);
            LLVMContextDispose(ctx);
        }
    }

    /**
     * Emitteert uitsluitend de LLVM IR tekst (.ll) van een module, zonder
     * validatie of codegen.
     *
     * Wordt gebruikt voor inspectie en voor tests die de frontend
     * → IR → LLVM-pipeline willen verifiëren. Let op: op dit platform
     * (x86_64-pc-windows-msvc, clang/MSVC only) crasht LLVM's eigen
     * {@code LLVMVerifyModule} natively (Itanium-«__gcc_personality_v0»
     * wordt op een Windows-target niet ondersteund); deze methode genereert
     * de IR dan ook zonder dat validatie-pad te bewandelen.
     */
    public void emitIRText(IRModule module,
                           CompileOptions opts,
                           Path llPath) throws CompileException {
        LLVMContextRef ctx     = LLVMContextCreate();
        LLVMModuleRef  llvmMod = LLVMModuleCreateWithNameInContext(module.name, ctx);
        LLVMBuilderRef builder = LLVMCreateBuilderInContext(ctx);

        try {
            var emitter = new LLVMModuleEmitter(ctx, llvmMod, builder, opts);
            emitter.emit(module);

            String triple = opts.targetTriple() != null
                    ? opts.targetTriple()
                    : LLVMGetDefaultTargetTriple().getString();
            LLVMSetTarget(llvmMod, new BytePointer(triple));

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

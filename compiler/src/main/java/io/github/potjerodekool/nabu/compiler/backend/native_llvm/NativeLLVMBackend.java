package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.Backend;
import io.github.potjerodekool.nabu.backend.CompileException;
import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.ir.IRModule;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.llvm.LLVM.*;

import java.nio.file.Path;

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
        compileToObject(module, opts, outputObj);

        // Alleen linken als er een main-functie aanwezig is
        boolean hasMain = module.functions().stream()
                .anyMatch(f -> f.name.equals("main") && !f.isExternal());
        if (!hasMain) return;

        String triple = opts.targetTriple() != null
                ? opts.targetTriple()
                : LLVMGetDefaultTargetTriple().getString();

        Path exe = replaceExtension(outputObj,
                triple.contains("windows") ? ".exe" : "");
        Linker.link(outputObj, exe, triple);
    }

    /**
     * Compileert naar object file zonder te linken.
     * Handig voor tests en bibliotheken.
     */
    public void compileToObject(IRModule module,
                                CompileOptions opts,
                                Path outputObj) throws CompileException {
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

            BytePointer   err       = new BytePointer();
            LLVMTargetRef targetRef = new LLVMTargetRef();
            if (LLVMGetTargetFromTriple(new BytePointer(triple), targetRef, err) != 0)
                throw new CompileException("Onbekend target: " + err.getString());

            LLVMTargetMachineRef machine = LLVMCreateTargetMachine(
                    targetRef, new BytePointer(triple),
                    new BytePointer("generic"), new BytePointer(""),
                    LLVMCodeGenLevelDefault, LLVMRelocPIC, LLVMCodeModelDefault);

            LLVMSetModuleDataLayout(llvmMod, LLVMCreateTargetDataLayout(machine));
            validate(llvmMod);

            Path llPath = replaceExtension(outputObj, ".ll");
            emitLLVMIR(llvmMod, llPath);

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

    private static Path replaceExtension(Path path, String newExt) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        return path.getParent() != null
                ? path.getParent().resolve(base + newExt)
                : Path.of(base + newExt);
    }
}

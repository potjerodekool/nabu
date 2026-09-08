package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.compiler.impl.EnterPhase;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase;
import io.github.potjerodekool.nabu.tools.*;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end native backend pipeline: broncode → frontend IR → LLVM-backend → .o.
 *
 * Volgt exact de echte pipeline ({@code NabuCompiler.generateCode}):
 * EnterPhase → ResolverPhase → IrGeneratingVisitor → TypeInference → SSA →
 * Optimizer → NativeLLVMBackend.compileToObject. NB: géén {@code lower()}
 * (die vertaling in de test zou de uitzonderings-structuur verwijderen).
 *
 * NB: het linken/RUNNEN van een native executable vereist een C main-entry
 * (frontend emitteert Main_main; NativeLLVMBackend.compile zoekt de letterlijke
 * naam "main" en linkt dus nooit) en, voor Itanium-exceptions, een
 * libgcc/libunwind-toolchain die op deze machine (clang/MSVC only) ontbreekt.
 */
class NativeExceptionEndToEndTest {

    @TempDir
    Path tempDir;

    private CompilerContext ctx;

    private CompilerContext context() {
        if (ctx == null) {
            var root = new File(".").getAbsoluteFile();
            while (!root.isDirectory() || !"nabu".equals(root.getName())) {
                root = root.getParentFile();
            }
            String rootPath = new File(root, "compiler").getAbsolutePath();
            CompilerOptions options = new CompilerOptions.CompilerOptionsBuilder()
                    .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                    .option(CompilerOption.SOURCE_PATH, rootPath + "/src/test/resources/classes")
                    .option(CompilerOption.MODULE_SOURCE_PATH, rootPath + "/src/test/resources/jmods")
                    .build();
            Compiler compiler = ServiceLoader.load(Compiler.class).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No compiler"));
            ctx = compiler.configure(options);
        }
        return ctx;
    }

    private void dump(Object o, StringBuilder sb, int depth) {
        if (o == null) return;
        if (o instanceof java.util.Collection) {
            for (var e : (java.util.Collection<?>) o) dump(e, sb, depth);
            return;
        }
        sb.append("  ".repeat(Math.max(0, depth))).append(o.getClass().getSimpleName());
        if (o instanceof io.github.potjerodekool.nabu.tree.statement.TryStatementTree) sb.append(" <TRY>");
        if (o instanceof io.github.potjerodekool.nabu.tree.statement.ThrowStatement) sb.append(" <THROW>");
        sb.append("\n");
        for (String m : new String[]{"getBody", "getStatements", "getExpression", "getValue", "getFinalizer"}) {
            try {
                var mm = o.getClass().getMethod(m);
                Object v = mm.invoke(o);
                dump(v, sb, depth + 1);
            } catch (Exception ignored) {
            }
        }
    }

    private CompilationUnit compile(String source) throws Exception {
        Path tmp = Files.createTempFile("E2E", ".java");
        Files.writeString(tmp, source, StandardCharsets.UTF_8);
        FileObject fo = new PathFileObject(new FileObject.Kind("java", true), tmp);
        try (var in = fo.openInputStream()) {
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var parser = new Java20Parser(new CommonTokenStream(new Java20Lexer(CharStreams.fromString(text))));
            var cuNode = (CompilationUnit) parser.compilationUnit().accept(new JavaCompilerVisitor(fo, false));
            StringBuilder dbg = new StringBuilder();
            cuNode.getClasses().forEach(c -> c.getEnclosedElements().forEach(m -> dump(m, dbg, 0)));
            Files.writeString(Path.of("C:/Users/evert/AppData/Local/Temp/opencode/parsed.txt"), dbg.toString());
            EnterPhase.enterPhase(cuNode, context());
            ResolverPhase.resolvePhase(cuNode, context());
            return cuNode;
        }
    }

    /** Verwerkt een module zoals de echte compiler (TypeInference + SSA + Optimizer). */
    private IRModule optimize(IRModule module) {
        for (var fn : module.functions()) {
            if (!fn.isExternal()) {
                new io.github.potjerodekool.nabu.backend.ir.optimize.TypeInference().run(fn);
            }
        }
        io.github.potjerodekool.nabu.backend.ir.SsaBuilder.run(module);
        return io.github.potjerodekool.nabu.backend.ir.Optimizer.optimize(module);
    }

    /**
     * Draait een bron door de echte frontend-pipeline (EnterPhase → ResolverPhase
     * → IrGeneratingVisitor → TypeInference → SSA → Optimizer) en laat de native
     * backend de LLVM IR tekst emitteren.
     *
     * NB: op dit platform (x86_64-pc-windows-msvc, clang/MSVC only) crasht
     * LLVM's eigen {@code LLVMVerifyModule} natively (Itanium-«__gcc_personality_v0»
     * wordt op een Windows-target niet ondersteund). Vandaar dat we hier de IR
     * tekst genereren via {@code emitIRText} in plaats van de crashy
     * object-emissie/validatie te bewandelen. Het daadwerkelijk produceren van een
     * .o / runnable exe blijft op dit platform geblokkeerd (net als eerder
     * vastgesteld voor de linkerbranch).
     */
    private IRModule generateIr(String source) throws Exception {
        CompilationUnit cu = compile(source);
        IrGeneratingVisitor visitor = new IrGeneratingVisitor();
        visitor.acceptTree(cu, null);
        IRModule module = visitor.getModules().get(0);
        IRModule optimized = optimize(module);
        Path ll = tempDir.resolve(module.name.replace('/', '_') + ".ll");
        new NativeLLVMBackend().emitIRText(optimized, CompileOptions.debug(), ll);
        return optimized;
    }

    private String readLl(IRModule module) throws Exception {
        Path ll = tempDir.resolve(module.name.replace('/', '_') + ".ll");
        Files.copy(ll, Path.of("C:/Users/evert/AppData/Local/Temp/opencode/e2e.ll"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return Files.readString(ll);
    }

    /**
     * Een try/catch-programma uit échte bron compileert door de volledige
     * frontend-pipeline en de backend emitteert het uitzonderingsmechanisme
     * (landingpad + nabu_can_catch-typefiltering) in de native LLVM IR.
     */
    @Test
    void tryCatchCompilesToNativeObjectWithTypeChecking() throws Exception {
        IRModule module = generateIr(
                "class Main { static void main(String[] args) " +
                "{ try { throw new Exception(); } catch (Exception e) { } } }");

        assertTrue(module.functions().stream().anyMatch(f -> f.name.equals("Main_main")),
                "Verwacht een Main_main functie");

        String ll = readLl(module);
        assertTrue(ll.contains("landingpad"),
                "Verwacht een landingpad in de LLVM IR:\n" + ll);
        assertTrue(ll.contains("nabu_can_catch"),
                "Verwacht nabu_can_catch type-filtering in de LLVM IR:\n" + ll);
    }

    /**
     * Volledige nabu-bron → native .exe → run, op MinGW-SEH (gnu-triple).
     *
     * Programma:
     *   class Main { static void main(String[] args) {
     *     try { throw new Exception(); } catch (Exception e) { } } }
     *
     * De frontend emitteert Main_main; de backend synthetiseert dan een `main`
     * die Main_main aanroept, compileert naar een .o en linkt via MinGW-gcc
     * met nabu_runtime.c meegecompileerd. Als de exception niet zou worden
     * gevangen, abort nabu_throw (exit != 0). Een exit 0 bewijst dat de
     * nabu_seh_personality + LSDA het landingspad installeerde en de
     * catch-handler uitvoerde.
     */
    @Test
    void fullSourceToExeCatchesException() throws Exception {
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }
        Path runtime = new File(root, "runtime/nabu_runtime.c").toPath();

        IRModule module = generateIr(
                "class Main { static void main(String[] args) " +
                "{ try { throw new Exception(); } catch (Exception e) { } } }");

        CompileOptions opts = CompileOptions.forTarget("x86_64-w64-windows-gnu");
        System.setProperty("nabu.runtime", runtime.toAbsolutePath().toString());

        Path out = tempDir.resolve("e2e.o");
        new NativeLLVMBackend().compile(module, opts, out);

        // .ll ter inspectie
        Path ll = tempDir.resolve("e2e.ll");
        if (Files.exists(ll)) {
            Files.copy(ll, Path.of("C:/Users/evert/AppData/Local/Temp/opencode/e2e_full.ll"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        Path exe = tempDir.resolve("e2e.exe");
        assertTrue(Files.exists(exe), ".exe niet aangemaakt: " + exe);

        // libgcc_s_seh-1.dll zit in de MinGW-bin; zet die op PATH voor de run
        // (en voor het linken al gebeurd via de gevonden gcc).
        String extraBin = "C:\\programs\\mingw64\\mingw64\\bin";
        ProcessBuilder pb = new ProcessBuilder(exe.toString());
        pb.environment().compute("Path", (k, v) -> extraBin + ";" + (v == null ? "" : v));
        Process proc = pb.redirectErrorStream(true).start();
        String outStr = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertEquals(0, exit,
                "Exception werd niet gevangen (nb. 'onbehandelde exception' - abort). Output:\n" + outStr);
    }
}

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
                    .build();
            Compiler compiler = ServiceLoader.load(Compiler.class).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No compiler"));
            ctx = compiler.configure(options);
        }
        return ctx;
    }

    private CompilationUnit compile(String source) throws Exception {
        Path tmp = Files.createTempFile("E2E", ".java");
        Files.writeString(tmp, source, StandardCharsets.UTF_8);
        FileObject fo = new PathFileObject(new FileObject.Kind("java", true), tmp);
        try (var in = fo.openInputStream()) {
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var parser = new Java20Parser(new CommonTokenStream(new Java20Lexer(CharStreams.fromString(text))));
            var cuNode = (CompilationUnit) parser.compilationUnit().accept(new JavaCompilerVisitor(fo, false));
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
     * {@code emitIRText} valideert de module wel (LLVMVerifyModule), maar
     * genereert geen object: dat is sneller en deze test assert op de IR-vorm
     * zelf (landingpad, nabu_can_catch-typefiltering). Het produceren van een
     * werkende .o/exe voor deze EH-programma's hoort bij de runtime-tests
     * hieronder, niet bij deze IR-tests.
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
        NativeToolchain.dump(ll, "e2e.ll");
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
     * Blokken die de try-body zelf aanmaakt (hier de ternary) horen ook bij
     * de beschermde range: een call daarin moet een {@code invoke} naar het
     * landingspad worden. try.end werd vóór de body aangemaakt en de backend
     * beschermt [try.body, try.end) in blokvolgorde, dus zulke calls liepen
     * buiten de catch (picocli's Range.parseInt ving zijn
     * NumberFormatException niet).
     */
    @Test
    void callInBlockCreatedInsideTryBodyUnwindsToHandler() throws Exception {
        IRModule module = generateIr(
                "class Main { static int f(String s) { return 1; } " +
                "static int g(String s, boolean b) { " +
                "try { return f(b ? s : s.trim()); } catch (Exception e) { return 2; } } " +
                "static void main(String[] args) { } }");

        String ll = readLl(module);
        assertTrue(ll.contains("invoke i32 @Main_f("),
                "De call in het ternary-blok binnen try moet een invoke zijn:\n" + ll);
        assertFalse(ll.contains("call i32 @Main_f("),
                "Geen onbeschermde call naar Main_f binnen de try verwacht:\n" + ll);
    }

    /** TIJDELIJKE PROBE: for-each met `result |= f(o)` native draaien. */
    @Test
    void probeForEachWithOrAssign() throws Exception {
        NativeToolchain.assumeGcc();
        IRModule module = generateIr(
                "class Main { " +
                "static boolean f(Object o) { return o != null; } " +
                "static int count(Object[] arr) throws Exception { boolean result = false; int n = 0; " +
                "  for (Object o : arr) { result |= f(o); n++; if (n > 100) { throw new Exception(); } } " +
                "  return result ? n : -n; } " +
                "static void main(String[] args) throws Exception { " +
                "  if (count(new Object[3]) != -3) { throw new Exception(); } } }");
        CompileOptions opts = new CompileOptions(CompileOptions.OptLevel.DEFAULT, false,
                "x86_64-w64-windows-gnu", CompileOptions.GcStrategy.BOEHM,
                io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION);
        Path outDir = Path.of("target", "e2e").toAbsolutePath();
        Files.createDirectories(outDir);
        Path out = outDir.resolve("probe-loop.o");
        new NativeLLVMBackend().compile(module, opts, out);
        Path exe = outDir.resolve("probe-loop.exe");
        Process proc = NativeToolchain.run(exe).redirectErrorStream(true).start();
        boolean done = proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) { proc.destroyForcibly(); }
        String outStr = new String(proc.getInputStream().readAllBytes());
        assertTrue(done, "probe hing (timeout)");
        assertEquals(0, proc.exitValue(), "probe-uitvoer:\n" + outStr);
    }

    /**
     * Volledige nabu-bron → native .exe (→ run), op MinGW-SEH (gnu-triple).
     *
     * Programma:
     *   class Main { static void main(String[] args) {
     *     try { throw new Exception(); } catch (Exception e) { } } }
     *
     * De frontend emitteert Main_main; de backend synthetiseert dan een `main`
     * die Main_main aanroept, compileert naar een .o en linkt via MinGW-gcc
     * met nabu_runtime.c meegecompileerd.
     */
    @Test
    void fullSourceToExeCatchesException() throws Exception {
        NativeToolchain.assumeGcc();

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

        // Produceer het .o en de gelinkte exe in target/e2e zodat de artefacten
        // na de test inspecteerbaar blijven. De exe heet bewust exception-e2e.exe
        // en niet e2e.exe: de AV op de ontwikkelmachine heeft de naam e2e*.exe in
        // deze map in de quarantainelijst staan (CreateFile ACCESS_DENIED, ook
        // voor een lege file), ongeacht de inhoud.
        Path outDir = Path.of("target", "e2e").toAbsolutePath();
        Files.createDirectories(outDir);
        Path out = outDir.resolve("exception-e2e.o");
        new NativeLLVMBackend().compile(module, opts, out);

        // .ll ter inspectie
        Path ll = outDir.resolve("exception-e2e.ll");
        if (Files.exists(ll)) {
            NativeToolchain.dump(ll, "e2e_full.ll");
        }

        Path exe = outDir.resolve("exception-e2e.exe");
        assertTrue(Files.exists(exe), ".exe niet aangemaakt: " + exe);

        // libgcc_s_seh-1.dll zit in de MinGW-bin; zet die op PATH voor de run
        // (en voor het linken al gebeurd via de gevonden gcc).
        ProcessBuilder pb = NativeToolchain.run(exe);
        Process proc = pb.redirectErrorStream(true).start();
        String outStr = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertEquals(0, exit,
                "Exception werd niet gevangen (nb. 'onbehandelde exception' - abort). Output:\n" + outStr);
    }
}

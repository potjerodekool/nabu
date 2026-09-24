package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.backend.ir.Optimizer;
import io.github.potjerodekool.nabu.backend.ir.SsaBuilder;
import io.github.potjerodekool.nabu.compiler.impl.EnterPhase;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase;
import io.github.potjerodekool.nabu.tools.Compiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnose-probe: pulpt de IR van een ternary-functie (pick(Z)I-shape) om de
 * dode-slot-anomalie te traceren. Per pass een dump.
 */
class TernaryIrDumpProbe {

    private static CompilerContextImpl context() throws Exception {
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }
        String rootPath = new File(root, "compiler").getAbsolutePath();
        CompilerOptions options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, rootPath + "/src/test/resources/classes")
                .build();
        Compiler compiler = java.util.ServiceLoader.load(Compiler.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("No compiler"));
        return (CompilerContextImpl) compiler.configure(options);
    }

    @Test
    void dumpTernaryIr() throws Exception {
        String source = "package mini;\n" +
                "public class Tern {\n" +
                "    int pick(boolean flag) {\n" +
                "        int x = flag ? 1 : 2;\n" +
                "        return flag ? x : x + 1;\n" +
                "    }\n" +
                "}\n";

        Path tmp = Files.createTempFile("TERN", ".java");
        Files.writeString(tmp, source, StandardCharsets.UTF_8);

        FileObject fo = new PathFileObject(new FileObject.Kind("java", true), tmp);
        try (var in = fo.openInputStream()) {
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var parser = new Java20Parser(new org.antlr.v4.runtime.CommonTokenStream(
                    new Java20Lexer(CharStreams.fromString(text))));
            var cu = (io.github.potjerodekool.nabu.tree.CompilationUnit)
                    parser.compilationUnit().accept(new JavaCompilerVisitor(fo, false));

            EnterPhase.enterPhase(cu, context());
            ResolverPhase.resolvePhase(cu, context());

            io.github.potjerodekool.nabu.compiler.backend.lower.Lower.lower(cu, context());

            IrGeneratingVisitor visitor = new IrGeneratingVisitor();
            visitor.acceptTree(cu, null);

            IRModule module = visitor.getModules().get(0);
            for (var fn : module.functions()) {
                if (!fn.isExternal()) {
                    new io.github.potjerodekool.nabu.backend.ir.optimize.TypeInference().run(fn);
                }
            }
            SsaBuilder.run(module);

            var pickFn = module.functions().stream()
                    .filter(f -> !f.isExternal())
                    .findFirst()
                    .orElseThrow();

            var dumper = new java.util.function.BiConsumer<String, IRFunction>() {
                @Override
                public void accept(final String tag, final IRFunction f) {
                var sbx = new StringBuilder("FN ").append(f.name).append('\n');
                for (var blk : f.blocks()) {
                    sbx.append("  BLOCK ").append(blk.label()).append('\n');
                    for (var ins : blk.instructions()) {
                        sbx.append("    ").append(ins).append('\n');
                    }
                }
                try {
                    Files.writeString(
                            Path.of("C:/Users/evert/AppData/Local/Temp/opencode/ternary-ir." + tag + ".txt"),
                            sbx.toString());
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                }
            };

            dumper.accept("p0.ssa", pickFn);
            new io.github.potjerodekool.nabu.backend.ir.optimize.ConstantFolder().run(pickFn);
            dumper.accept("p1.cf", pickFn);
            new io.github.potjerodekool.nabu.backend.ir.optimize.CopyPropagation().run(pickFn);
            dumper.accept("p2.cp", pickFn);
            new io.github.potjerodekool.nabu.backend.ir.optimize.GlobalValueNumbering().run(pickFn);
            dumper.accept("p3.gvn", pickFn);
            new io.github.potjerodekool.nabu.backend.ir.optimize.DeadCodeElimination().run(pickFn);
            dumper.accept("p4.dce", pickFn);

            var optimized = Optimizer.optimize(module);

            var sbOpt = new StringBuilder();
            for (var fn : optimized.functions()) {
                sbOpt.append("FN ").append(fn.name).append("\n");
                for (var blk : fn.blocks()) {
                    sbOpt.append("  BLOCK ").append(blk.label()).append("\n");
                    for (var ins : blk.instructions()) {
                        sbOpt.append("    ").append(ins).append("\n");
                    }
                }
            }
            Files.writeString(Path.of("C:/Users/evert/AppData/Local/Temp/opencode/ternary-ir.after.txt"), sbOpt.toString());

            new NativeLLVMBackend().emitIRText(optimized, io.github.potjerodekool.nabu.backend.CompileOptions.debug(),
                    Path.of("C:/Users/evert/AppData/Local/Temp/opencode/ternary.ll"));
            Files.writeString(Path.of("C:/Users/evert/AppData/Local/Temp/opencode/ternary-ll.done"), "ok");
        }
    }
}
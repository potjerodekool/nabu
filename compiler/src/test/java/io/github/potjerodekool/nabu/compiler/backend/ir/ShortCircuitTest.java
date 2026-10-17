package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.tools.Compiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code &&} en {@code ||} evalueren hun rechterkant alleen als de linkerkant de
 * uitkomst nog niet vastlegt (JLS 15.23/15.24).
 *
 * <p>De IR-generator vertaalde beide naar een gewone AND/OR over twee vooraf
 * uitgerekende operanden. Picocli's {@code Range}-constructor deed zo
 * {@code originalValue != null && originalValue.contains("+")} en riep
 * {@code contains} op null aan.
 */
class ShortCircuitTest {

    @TempDir
    Path tempDir;

    @Test
    void conditionalAndEvaluatesRightSideOnlyInItsOwnBlock() throws Exception {
        final var fn = findFunction(compile("package mini;\n"
                + "public class Mini {\n"
                + "    static boolean nonEmpty(String s) {\n"
                + "        return s != null && !s.isEmpty();\n"
                + "    }\n"
                + "}\n"), "Mini_nonEmpty");

        final IRBasicBlock callBlock = blockCalling(fn, "isEmpty");
        assertTrue(callBlock.label().startsWith("and.rhs"),
                "isEmpty moet in het and.rhs-blok staan, niet in " + callBlock.label());
        assertNoLogicalBinaryOp(fn);
    }

    @Test
    void conditionalOrEvaluatesRightSideOnlyInItsOwnBlock() throws Exception {
        final var fn = findFunction(compile("package mini;\n"
                + "public class Mini {\n"
                + "    static boolean blank(String s) {\n"
                + "        return s == null || s.isEmpty();\n"
                + "    }\n"
                + "}\n"), "Mini_blank");

        final IRBasicBlock callBlock = blockCalling(fn, "isEmpty");
        assertTrue(callBlock.label().startsWith("or.rhs"),
                "isEmpty moet in het or.rhs-blok staan, niet in " + callBlock.label());
        assertNoLogicalBinaryOp(fn);
    }

    /**
     * Als conditie wordt {@code &&} een sprongketen (zoals javac): de then-tak is
     * alleen bereikbaar vanuit de rechterkant. Een variabele die daar wordt
     * toegekend is in de then-tak dus op elk pad gezet — via een boolean-cel
     * kwam {@code pos} ongeïnitialiseerd binnen en weigerde de JVM-verifier
     * picocli's {@code quoteElements}.
     */
    @Test
    void conditionalAndInIfBranchesDirectlyFromRightSide() throws Exception {
        final var fn = findFunction(compile("package mini;\n"
                + "public class Mini {\n"
                + "    static int find(String s) {\n"
                + "        int pos;\n"
                + "        if (s != null && (pos = s.indexOf(\"x\")) >= 0) {\n"
                + "            return pos;\n"
                + "        }\n"
                + "        return -1;\n"
                + "    }\n"
                + "}\n"), "Mini_find");

        final var thenLabels = new ArrayList<String>();
        for (final var block : fn.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.CondBranch br
                        && br.trueLabel().startsWith("if.then")) {
                    thenLabels.add(block.label());
                }
            }
        }
        assertTrue(!thenLabels.isEmpty() && thenLabels.stream().allMatch(l -> l.startsWith("and.rhs")),
                "if.then mag alleen vanuit and.rhs bereikbaar zijn, wel vanuit: " + thenLabels);
        assertNoLogicalBinaryOp(fn);
    }

    private static IRBasicBlock blockCalling(final IRFunction fn, final String method) {
        for (final var block : fn.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.Call call
                        && call.function() != null
                        && call.function().endsWith("_" + method)) {
                    return block;
                }
            }
        }
        throw new AssertionError("geen call naar " + method + " in " + fn.name);
    }

    private static void assertNoLogicalBinaryOp(final IRFunction fn) {
        for (final var block : fn.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.BinaryOp op) {
                    assertFalse(op.op() == IRInstruction.BinaryOp.Op.AND
                                    || op.op() == IRInstruction.BinaryOp.Op.OR,
                            "Geen niet-kortsluitende AND/OR verwacht in " + block.label());
                }
            }
        }
    }

    private static IRFunction findFunction(final List<IRFunction> functions,
                                           final String suffix) {
        return functions.stream()
                .filter(f -> !f.isExternal())
                .filter(f -> f.name.endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("functie niet gevonden: " + suffix));
    }

    private List<IRFunction> compile(final String source) throws Exception {
        final var file = tempDir.resolve("src/mini/Mini.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);

        final var context = context();
        final FileObject fo = new PathFileObject(new FileObject.Kind("java", true), file);
        final String text;
        try (var in = fo.openInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        final var parser = new io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser(
                new CommonTokenStream(
                        new io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer(
                                CharStreams.fromString(text))));
        final var cu = (io.github.potjerodekool.nabu.tree.CompilationUnit)
                parser.compilationUnit().accept(
                        new io.github.potjerodekool.nabu.compiler.lang.support.java
                                .JavaCompilerVisitor(fo, false));

        io.github.potjerodekool.nabu.compiler.impl.EnterPhase.enterPhase(cu, context);
        io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase
                .resolvePhase(cu, context);
        io.github.potjerodekool.nabu.compiler.backend.lower.Lower.lower(cu, context);

        final var visitor = new IrGeneratingVisitor();
        visitor.acceptTree(cu, null);

        final var out = new ArrayList<IRFunction>();
        for (final var module : visitor.getModules()) {
            out.addAll(module.functions());
        }
        return out;
    }

    private static CompilerContextImpl context() {
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }
        final var rootPath = new File(root, "compiler").getAbsolutePath();
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, rootPath + "/src/test/resources/classes")
                .build();
        final var compiler = java.util.ServiceLoader.load(Compiler.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No compiler"));
        return (CompilerContextImpl) compiler.configure(options);
    }
}

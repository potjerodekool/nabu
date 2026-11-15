package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * JLS 5.2: a returned primitive is widened to the return type of the method (also of a lambda's body, whose return
 * type comes from the functional interface). Without it LLVM rejects {@code ret i64} in a function that returns a
 * double.
 */
class ReturnWideningTest {

    @TempDir
    Path tempDir;

    @Test
    void returnedPrimitivesHaveTheReturnTypeOfTheirFunction() throws Exception {
        final var functions = compile("""
                package mini;
                public class Mini {
                    interface Task { double run(int n); }
                    interface Count { long count(int n); }

                    static long l(int n) { return n; }
                    static int i(int n) { return n; }

                    static double longToDouble(int n) { return l(n); }
                    static long intToLong(int n) { return i(n); }
                    static double intToDouble(int n) { return i(n); }
                    static float intToFloat(int n) { return i(n); }

                    static double viaLambda(int n) {
                        Task t = x -> l(x);
                        return t.run(n);
                    }

                    static long viaIntLambda(int n) {
                        Count c = x -> i(x);
                        return c.count(n);
                    }
                }
                """);
        final var problems = new ArrayList<String>();
        for (final IRFunction fn : functions) {
            for (final var block : fn.blocks()) {
                for (final var in : block.instructions()) {
                    if (in instanceof IRInstruction.Return r && r.value() != null
                            && !String.valueOf(r.value().type()).equals(String.valueOf(fn.returnType))) {
                        problems.add(fn.name + " returns " + r.value().type() + " but is declared " + fn.returnType);
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
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
        final var parser = new io.github.potjerodekool.nabu.compiler.lang.support.java.Java25Parser(
                new CommonTokenStream(
                        new io.github.potjerodekool.nabu.compiler.lang.support.java.Java25Lexer(
                                CharStreams.fromString(text))));
        final var cu = (io.github.potjerodekool.nabu.tree.CompilationUnit)
                parser.compilationUnit().accept(
                        new io.github.potjerodekool.nabu.compiler.lang.support.java
                                .JavaCompilerVisitor(fo, false));
        io.github.potjerodekool.nabu.compiler.impl.EnterPhase.enterPhase(cu, context);
        io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase.resolvePhase(cu, context);
        io.github.potjerodekool.nabu.compiler.backend.lower.Lower.lower(cu, context);
        final var visitor = new IrGeneratingVisitor();
        visitor.acceptTree(cu, null);
        final var out = new ArrayList<IRFunction>();
        for (final var module : visitor.getModules()) {
            out.addAll(module.functions());
        }
        assertFalse(out.isEmpty(), "no functions generated");
        return out;
    }

    private static CompilerContextImpl context() throws Exception {
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, System.getProperty("java.home"))
                .build();
        final var compiler = java.util.ServiceLoader.load(Compiler.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No compiler"));
        return (CompilerContextImpl) compiler.configure(options);
    }
}

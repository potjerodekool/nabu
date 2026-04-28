package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.CompilerPhase;
import io.github.potjerodekool.nabu.compiler.NabuCompilerTest;
import io.github.potjerodekool.nabu.compiler.impl.EnterPhase;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.testing.InMemoryFileObject;
import io.github.potjerodekool.nabu.testing.TreePrinter;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Function;

import static io.github.potjerodekool.nabu.compiler.backend.lower.Lower.lower;
import static org.junit.jupiter.api.Assertions.*;

class ResolverPhaseTest extends NabuCompilerTest {

    @Test
    void visitLambdaExpression() throws IOException {
        var cu = (CompilationUnit) parse(
                new InMemoryFileObject("""
                        import java.util.function.Function;
                        
                        public class MyClass {

                            void myFunction() {
                                Function<Integer, String> f = x -> String.valueOf(x);
                            }

                        }
                        """, "MyClass.java"),
                Java20Parser::compilationUnit
        );

        final var text = TreePrinter.print(cu);
        System.out.println(text);

        cu = process(cu, CompilerPhase.ENTER);
        cu = ResolverPhase.resolvePhase(cu, getCompilerContext());

        System.out.println(cu);
    }

    @Test
    void visitMethodInvocationWithLambdaExpression() throws IOException {
        var cu = (CompilationUnit) parse(
                new InMemoryFileObject("""
                        import java.util.function.Function;
                        import java.util.List;
                        
                        public class MyClass {

                            void myFunction(final List<Integer> list) {
                                list.forEach((Integer value) -> {
                                });
                            }

                        }
                        """, "MyClass.java"),
                Java20Parser::compilationUnit
        );

        final var text = TreePrinter.print(cu);
        System.out.println(text);

        cu = process(cu, CompilerPhase.ENTER);
        cu = ResolverPhase.resolvePhase(cu, getCompilerContext());

        System.out.println(cu);
    }
}
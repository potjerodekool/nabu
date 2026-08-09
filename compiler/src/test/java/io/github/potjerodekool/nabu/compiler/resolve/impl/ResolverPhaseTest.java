package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.CompilerPhase;
import io.github.potjerodekool.nabu.compiler.InMemoryFileObject;
import io.github.potjerodekool.nabu.compiler.NabuCompilerTest;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;

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
        // list.forEach((Integer value) -> {

        var cu = (CompilationUnit) parse(
                new InMemoryFileObject("""
                        import java.util.function.Function;
                        import java.util.List;
                        
                        public class MyClass {

                            void myFunction(final List<Integer> list) {
                                list.forEach((value) -> {
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
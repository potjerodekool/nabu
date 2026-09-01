package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.CompilerPhase;
import io.github.potjerodekool.nabu.compiler.InMemoryFileObject;
import io.github.potjerodekool.nabu.compiler.NabuCompilerTest;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ResolverPhaseTest extends NabuCompilerTest {

    private CompilationUnit parseAndResolve(String source) throws IOException {
        var cu = (CompilationUnit) parse(
                new InMemoryFileObject(source, "Test.java"),
                Java20Parser::compilationUnit
        );
        cu = process(cu, CompilerPhase.ENTER);
        return ResolverPhase.resolvePhase(cu, getCompilerContext());
    }

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

        cu = process(cu, CompilerPhase.ENTER);
        cu = ResolverPhase.resolvePhase(cu, getCompilerContext());

        assertNotNull(cu, "Lambda expressie zou correct geresolved moeten worden");
    }

    @Test
    void visitMethodInvocationWithLambdaExpression() throws IOException {
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

        cu = process(cu, CompilerPhase.ENTER);
        cu = ResolverPhase.resolvePhase(cu, getCompilerContext());

        assertNotNull(cu, "Lambda in method invocation zou correct geresolved moeten worden");
    }

    @Test
    void visitNewArrayWithInitializer() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    int[] getValues() {
                        return new int[] { 1, 2, 3 };
                    }
                }
                """);

        assertNotNull(cu, "new int[] { 1, 2, 3 } zou geresolved moeten worden");
    }

    @Test
    void visitStringPlusInt() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    String format(int value) {
                        return "value=" + value;
                    }
                }
                """);

        assertNotNull(cu, "String + int zou geresolved moeten worden");
        final var text = TreePrinter.print(cu);
        assertTrue(text.contains("value=") && text.contains("+"),
                "Tree zou string concat moeten bevatten");
    }

    @Test
    void visitStringValueOfInt() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    String convert(int value) {
                        return String.valueOf(value);
                    }
                }
                """);

        assertNotNull(cu, "String.valueOf(int) zou geresolved moeten worden");
        final var text = TreePrinter.print(cu);
        assertTrue(text.contains("valueOf"), "Tree zou valueOf method call moeten bevatten");
    }

    @Test
    void visitMultiDimensionalArray() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    int[][] createMatrix() {
                        return new int[3][4];
                    }
                }
                """);

        assertNotNull(cu, "new int[3][4] zou geresolved moeten worden");
    }

    @Test
    void visitArrayInitializerList() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    String[] getNames() {
                        return new String[] { "Alice", "Bob" };
                    }
                }
                """);

        assertNotNull(cu, "String[] initializer zou geresolved moeten worden");
        final var text = TreePrinter.print(cu);
        assertTrue(text.contains("String[]"), "Tree zou String array type moeten bevatten");
    }

    @Test
    void visitArrayCreationWithDimension() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    int[] createArray(int size) {
                        return new int[size];
                    }
                }
                """);

        assertNotNull(cu, "new int[size] zou geresolved moeten worden");
    }

    @Test
    void visitArrayLength() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    int getLength(int[] arr) {
                        return arr.length;
                    }
                }
                """);

        assertNotNull(cu, "arr.length zou geresolved moeten worden");
    }

    @Test
    void visitArrayAccess() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    int getElement(int[] arr, int index) {
                        return arr[index];
                    }
                }
                """);

        assertNotNull(cu, "arr[index] zou geresolved moeten worden");
    }

    @Test
    void visitArrayInForLoop() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    int sum(int[] values) {
                        int result = 0;
                        int i = 0;
                        while (i < values.length) {
                            result = result + values[i];
                            i = i + 1;
                        }
                        return result;
                    }
                }
                """);

        assertNotNull(cu, "Array in while loop zou geresolved moeten worden");
    }

    @Test
    void visitBinaryExpressionWithStringConcat() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    String greet(String name, int age) {
                        return name + " is " + age + " years old";
                    }
                }
                """);

        assertNotNull(cu, "Meervoudige string concat zou geresolved moeten worden");
    }

    @Test
    void visitArrayInMethodParameter() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    void process(int[] data) {
                    }
                }
                """);

        assertNotNull(cu, "Array parameter zou geresolved moeten worden");
    }

    @Test
    void visitStringConcatWithNull() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    String format(String s) {
                        return "value=" + s;
                    }
                }
                """);

        assertNotNull(cu, "String + String zou geresolved moeten worden");
    }

    @Test
    void visitIntToStringValueOf() throws IOException {
        final var cu = parseAndResolve("""
                public class MyClass {
                    String convert(double value) {
                        return String.valueOf(value);
                    }
                }
                """);

        assertNotNull(cu, "String.valueOf(double) zou geresolved moeten worden");
    }
}

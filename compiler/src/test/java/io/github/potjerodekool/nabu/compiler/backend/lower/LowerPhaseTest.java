package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.CompilerPhase;
import io.github.potjerodekool.nabu.compiler.InMemoryFileObject;
import io.github.potjerodekool.nabu.compiler.NabuCompilerTest;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class LowerPhaseTest extends NabuCompilerTest {

    private CompilationUnit parseAndLower(String source) throws IOException {
        var cu = (CompilationUnit) parse(
                new InMemoryFileObject(source, "Test.java"),
                Java20Parser::compilationUnit
        );
        return process(cu, CompilerPhase.LOWER);
    }

    @Test
    void lowerStringPlusIntNoWidening() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    String format(int value) {
                        return "value=" + value;
                    }
                }
                """);

        assertNotNull(cu, "String + int zou door de lower phase moeten komen");
        final var text = TreePrinter.print(cu);
        assertTrue(text.contains("value="), "Tree zou string literal moeten bevatten");
    }

    @Test
    void lowerStringConcatMultipleOperands() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    String greet(String name, int age) {
                        return name + " is " + age + " years old";
                    }
                }
                """);

        assertNotNull(cu, "Meervoudige string concat zou door de lower phase moeten komen");
    }

    @Test
    void lowerNewArrayWithInitializer() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    int[] getValues() {
                        return new int[] { 1, 2, 3 };
                    }
                }
                """);

        assertNotNull(cu, "new int[] { 1, 2, 3 } zou door de lower phase moeten komen");
    }

    @Test
    void lowerNewArrayWithDimension() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    int[] createArray(int size) {
                        return new int[size];
                    }
                }
                """);

        assertNotNull(cu, "new int[size] zou door de lower phase moeten komen");
    }

    @Test
    void lowerMultiDimensionalArray() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    int[][] createMatrix() {
                        return new int[3][4];
                    }
                }
                """);

        assertNotNull(cu, "new int[3][4] zou door de lower phase moeten komen");
    }

    @Test
    void lowerStringArrayInitializer() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    String[] getNames() {
                        return new String[] { "Alice", "Bob" };
                    }
                }
                """);

        assertNotNull(cu, "String[] initializer zou door de lower phase moeten komen");
    }

    @Test
    void lowerArrayAccess() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    int getElement(int[] arr, int index) {
                        return arr[index];
                    }
                }
                """);

        assertNotNull(cu, "arr[index] zou door de lower phase moeten komen");
        final var text = TreePrinter.print(cu);
        assertTrue(text.contains("["), "Tree zou array access syntax moeten bevatten");
    }

    @Test
    void lowerArrayLength() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    int getLength(int[] arr) {
                        return arr.length;
                    }
                }
                """);

        assertNotNull(cu, "arr.length zou door de lower phase moeten komen");
        final var text = TreePrinter.print(cu);
        assertTrue(text.contains("length"), "Tree zou length field access moeten bevatten");
    }

    @Test
    void lowerArraySumLoop() throws IOException {
        final var cu = parseAndLower("""
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

        assertNotNull(cu, "Array som in while loop zou door de lower phase moeten komen");
    }

    @Test
    void lowerStringConcatWithNull() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    String format(String s) {
                        return "value=" + s;
                    }
                }
                """);

        assertNotNull(cu, "String + String zou door de lower phase moeten komen");
    }

    @Test
    void lowerIntBinaryExpression() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    int add(int a, int b) {
                        return a + b;
                    }
                }
                """);

        assertNotNull(cu, "int + int zou door de lower phase moeten komen");
    }

    @Test
    void lowerBooleanComparison() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    boolean isPositive(int value) {
                        return value > 0;
                    }
                }
                """);

        assertNotNull(cu, "value > 0 zou door de lower phase moeten komen");
    }

    @Test
    void lowerMethodInvocation() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    String valueOf(int value) {
                        return String.valueOf(value);
                    }
                }
                """);

        assertNotNull(cu, "String.valueOf(int) zou door de lower phase moeten komen");
    }

    @Test
    void lowerDoubleValueOf() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    String valueOf(double value) {
                        return String.valueOf(value);
                    }
                }
                """);

        assertNotNull(cu, "String.valueOf(double) zou door de lower phase moeten komen");
    }

    @Test
    void lowerChainedBinaryExpressions() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    int calculate(int a, int b, int c) {
                        return a + b * c;
                    }
                }
                """);

        assertNotNull(cu, "a + b * c zou door de lower phase moeten komen");
    }

    @Test
    void lowerArrayInMethodParameter() throws IOException {
        final var cu = parseAndLower("""
                public class MyClass {
                    void process(int[] data) {
                    }
                }
                """);

        assertNotNull(cu, "Array parameter zou door de lower phase moeten komen");
    }

}

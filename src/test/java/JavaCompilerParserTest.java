package io.github.potjerodekool.nabu.compiler.lang.support.java;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.antlr.v4.runtime.RecognitionException;

public class JavaCompilerParserTest {
    
    @Test
    public void testValidClassDeclaration() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testInvalidSyntax() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { int x = 5  " .getBytes());
        assertThrows(RecognitionException.class, () -> JavaCompilerParser.parse(inputStream));
    }

    @Test
    public void testEmptyInput() {
        InputStream inputStream = new ByteArrayInputStream(new byte[0]);
        assertThrows(RecognitionException.class, () -> JavaCompilerParser.parse(inputStream));
    }

    @Test
    public void testValidMethodWithParameters() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method(int x, String y) { System.out.println("Hello World"); } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testMethodWithIfStatement() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { if (true) { int x = 5; } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testMethodWithLoopAndAssignment() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { int i = 0; while (i < 5) { i++; } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testMethodWithSwitchStatement() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { switch (5) { case 5: break; default: System.out.println("Default"); } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testMethodWithNestedTryCatch() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { try { try { throw new Exception("Inner"); } catch (Exception e) { } } catch (Exception e) { } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testMethodWithForEachLoop() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { String[] arr = {"Hello", "World"}; for (String s : arr) { System.out.println(s); } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testMethodWithSynchronizedBlock() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { synchronized (this) { System.out.println("Locked"); } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testMethodWithMultipleCatchClauses() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { try { throw new Exception("Test"); } catch (Exception e) { } catch (Error e) { } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { try { throw new Exception("Test"); } catch (Exception e) { } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method(int x, String y) { } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testValidLoopStatement() {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { for(int i=0; i<10; i++) { } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }
}
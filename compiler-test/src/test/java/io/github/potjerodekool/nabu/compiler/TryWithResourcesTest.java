package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.test.CompilerTest;
import org.junit.jupiter.api.Test;

public class TryWithResourcesTest {

    @Test
    public void testTryWithResources() {
        final var result = CompilerTest.compile(
            "class Test {\n" +
            "    void method() throws Exception {\n" +
            "        try (var reader = new java.io.BufferedReader(new java.io.StringReader(\"test\"))) {\n" +
            "            System.out.println(reader.readLine());\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        assert result != null;
    }

    @Test
    public void testFinallyBlock() {
        final var result = CompilerTest.compile(
            "class Test {\n" +
            "    void method() {\n" +
            "        try {\n" +
            "            System.out.println(\"test\");\n" +
            "        } finally {\n" +
            "            System.out.println(\"finally\");\n" +
            "        }\n" +
            "    }\n" +
            "}\n");

        assert result != null;
    }
}
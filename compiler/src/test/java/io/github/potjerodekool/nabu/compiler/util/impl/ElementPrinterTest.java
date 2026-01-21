package io.github.potjerodekool.nabu.compiler.util.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static io.github.potjerodekool.nabu.test.TestUtils.fixLines;
import static io.github.potjerodekool.nabu.test.TestUtils.readResource;
import static org.junit.jupiter.api.Assertions.*;

class ElementPrinterTest {

    @Test
    void test() throws IOException {
        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .flags(Flags.PUBLIC + Flags.FINAL)
                .simpleName("SomeClass")
                .superclass(null)
                .build();

        final var writer = new StringWriter();
        ElementPrinter.print(clazz, writer);
        final var actual = fixLines(writer.toString());
        final var expected = readResource("ElementPrinterTest.txt");
        assertEquals(expected, actual);
    }
}
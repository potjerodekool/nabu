package io.github.potjerodekool.nabu.compiler.lang.support.kotlin;

import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.testing.TreePrinter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KotlinLanguageSupportTest extends AbstractCompilerTest {

    @Test
    void parse() {
        final var parser = new KotlinLanguageSupport();
        final var fileObject = createFileObject(
                loadResource("KotlinLanguageSupport/example/Customer.kt")
        );

        final var cu = parser.parse(
                fileObject,
                getCompilerContext()
        );

        final var actual = TreePrinter.print(cu);
        assertEquals(
                """
                        package example;
                        
                        class Customer {
                        }
                        """,
                actual
        );

    }
}
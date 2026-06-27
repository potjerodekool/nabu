package io.github.potjerodekool.lang.support.nabu;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

class NabuCompilerParserTest {

    @Test
    void parse() throws IOException {
        final var input = new ByteArrayInputStream("""
        class Test {
            valid
        }
        """.getBytes());

        final var cu = NabuCompilerParser.parse(input);
        System.out.println(cu);
    }
}
package io.github.potjerodekool.lang.support.nabu;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.io.InputStream;

public final class NabuCompilerParser {

    private NabuCompilerParser() {
    }

    public static NabuParser.CompilationUnitContext parse(final InputStream inputStream) throws IOException {
        final var inputSteam = CharStreams.fromStream(inputStream);
        final var lexer = new NabuLexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new NabuParser(tokens);
        return parser.compilationUnit();
    }
}

package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.io.InputStream;

public class NabuCompilerParser {

    public NabuParser.CompilationUnitContext parse(final InputStream inputStream) throws IOException {
        final var inputSteam = CharStreams.fromStream(inputStream);
        final var lexer = new NabuLexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new NabuParser(tokens);
        return parser.compilationUnit();
    }
}

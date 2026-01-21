package io.github.potjerodekool.nabu.compiler.lang.support.java;

import io.github.potjerodekool.nabu.Java20Lexer;
import io.github.potjerodekool.nabu.Java20Parser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.io.InputStream;

public final class JavaCompilerParser {

    private JavaCompilerParser() {
    }

    public static Java20Parser.CompilationUnitContext parse(final InputStream inputStream) throws IOException {
        final var inputSteam = CharStreams.fromStream(inputStream);
        final var lexer = new Java20Lexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java20Parser(tokens);
        return parser.compilationUnit();
    }
}

package io.github.potjerodekool.nabu.compiler.frontend.parser.nabu;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;

import java.io.IOException;
import java.io.InputStream;
import java.util.BitSet;

public final class NabuCompilerParser {

    private NabuCompilerParser() {
    }

    public static NabuParser.CompilationUnitContext parse(final InputStream inputStream) throws IOException {
        final var inputSteam = CharStreams.fromStream(inputStream);
        final var lexer = new NabuLexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new NabuParser(tokens);
        parser.addErrorListener(new SimpleErrorListener());
        return parser.compilationUnit();
    }
}

class SimpleErrorListener implements ANTLRErrorListener {

    @Override
    public void syntaxError(final Recognizer<?, ?> recognizer, final Object o, final int i, final int i1, final String s, final RecognitionException e) {
        //System.out.println("syntaxError");
    }

    @Override
    public void reportAmbiguity(final Parser parser, final DFA dfa, final int i, final int i1, final boolean b, final BitSet bitSet, final ATNConfigSet atnConfigSet) {
        //System.out.println("ambiguity");
    }

    @Override
    public void reportAttemptingFullContext(final Parser parser, final DFA dfa, final int i, final int i1, final BitSet bitSet, final ATNConfigSet atnConfigSet) {
    }

    @Override
    public void reportContextSensitivity(final Parser parser, final DFA dfa, final int i, final int i1, final int i2, final ATNConfigSet atnConfigSet) {
    }
}
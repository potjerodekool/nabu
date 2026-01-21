package io.github.potjerodekool.nabu.compiler.lang.support.kotlin;

//import io.github.potjerodekool.nabu.compiler.lang.support.kotlin.KotlinParser;
import io.github.potjerodekool.nabu.compiler.lang.support.kotlin.KotlinParser.KotlinFileContext;
//import io.github.potjerodekool.nabu.compiler.lang.support.kotlin.KotlinLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.io.InputStream;

public final class KotlinCompilerParser {

    private KotlinCompilerParser() {
    }

    public static KotlinFileContext parse(final InputStream inputStream) throws IOException {
        final var inputSteam = CharStreams.fromStream(inputStream);
        final var lexer = new KotlinLexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new KotlinParser(tokens);
        return parser.kotlinFile();
    }
}

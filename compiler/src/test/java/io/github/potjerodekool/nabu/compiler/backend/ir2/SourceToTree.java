package io.github.potjerodekool.nabu.compiler.backend.ir2;

import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.testing.InMemoryFileObject;
import io.github.potjerodekool.nabu.tree.element.Function;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public final class SourceToTree {

    private SourceToTree() {
    }

    public static Function createMethod(final String code) throws IOException {
        final var inputStream = new ByteArrayInputStream(code.getBytes());
        final var inputSteam = CharStreams.fromStream(inputStream);
        final var lexer = new Java20Lexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java20Parser(tokens);
        final var methodDeclaration = parser.methodDeclaration();
        final var fileObject = new InMemoryFileObject("mem", "mem.java");

        final var visitor = new JavaCompilerVisitor(fileObject, false);
        return (Function) methodDeclaration.accept(visitor);
    }
}


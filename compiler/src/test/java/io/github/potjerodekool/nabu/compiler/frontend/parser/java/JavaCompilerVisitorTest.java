package io.github.potjerodekool.nabu.compiler.frontend.parser.java;

import io.github.potjerodekool.nabu.Java20Lexer;
import io.github.potjerodekool.nabu.Java20Parser;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.io.NabuFileObject;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;
import java.util.function.Function;

class JavaCompilerVisitorTest {

    @Test
    void test() throws IOException {
        final var root = Paths.get("src/main/java");
        Files.walkFileTree(root, new SimplePathVisitor(path -> {
            try {
                final var data = new String(Files.readAllBytes(path));
                parse(data, Java20Parser::start_);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
    }

    private void parse(final String code,
                       final Function<Java20Parser, ParseTree> function1) {
        final var parser = createParser(code);
        final var fileObject = new NabuFileObject(
                new FileObject.Kind(".nabu", true),
                Paths.get("SomeClass.class")
        );

        final var visitor = new JavaCompilerVisitor(fileObject);
        final var printer = new TreePrinter();
        final ParseTree functionResult = function1.apply(parser);
        final var visitorResult = functionResult.accept(visitor);
    }

    private static Java20Parser createParser(final String code) {
        final var inputSteam = CharStreams.fromString(code);
        final var lexer = new Java20Lexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        return new Java20Parser(tokens);
    }

}

class SimplePathVisitor extends SimpleFileVisitor<Path> {

    private final Consumer<Path> pathConsumer;

    SimplePathVisitor(final Consumer<Path> pathConsumer) {
        this.pathConsumer = pathConsumer;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
        if (!file.getFileName().toString().endsWith(".java")) {
            pathConsumer.accept(file);
        }

        return super.visitFile(file, attrs);
    }
}
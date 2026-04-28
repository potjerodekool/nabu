package io.github.potjerodekool.nabu.compiler.frontend.parser.java;

import io.github.potjerodekool.nabu.compiler.frontend.parser.ASTPrinter;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.test.JavaLangTreeAssert;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import io.github.potjerodekool.nabu.tree.Tree;
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

    protected void parseAndAssert(final String code,
                                  final Function<Java20Parser, ParseTree> parseTreeBuilder) {
        JavaLangTreeAssert.INSTANCE.parseAndAssert(code, parseTreeBuilder);
    }

    public void parseAndAssert(final String code,
                               final Function<Java20Parser, ParseTree> parseTreeBuilder,
                               final String actualPrefix) {
        JavaLangTreeAssert.INSTANCE.parseAndAssert(code, parseTreeBuilder, actual -> actualPrefix + actual);
    }

    public void parseAndAssert(final String code,
                               final Function<Java20Parser, ParseTree> parseTreeBuilder,
                               final Function<String, String> actualTransformer) {
        JavaLangTreeAssert.INSTANCE.parseAndAssert(code, parseTreeBuilder, actualTransformer);
    }

    @Test
    void testClassDeclaration() {
        parse("""
                class SomeClass<A,B extends A> {
                
                    public <C> C work(final A a) {
                        return null;
                    }
                }
                """, Java20Parser::normalClassDeclaration);
    }

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

    @Test
    void testCast() {
        final var tree = parse("""
                class SomeClass {
                
                    public void work(final A a) {
                        final var list = new ArrayList<Integer>();
                        var count = 0;
                        count += ((Integer) list.get(0)).intValue();

                        return null;
                    }
                }
                """, Java20Parser::normalClassDeclaration);

        final var astText = ASTPrinter.print(tree);
        System.out.println(astText);
    }

    @Test
    void testPrimaryNoNewArrayParenthesizedExpression() {
        final var tree = parse("""
                ((Integer) list.get(0)).intValue()
                """, Java20Parser::expression);

        final var astText = ASTPrinter.print(tree);
        System.out.println(astText);
    }

    @Test
    void testPrimaryNoNewArrayLiteral() {
        final var tree = parse("""
                "".length()
                """, Java20Parser::expression);

        final var astText = ASTPrinter.print(tree);
        System.out.println(astText);
    }

    private Tree parse(final String code,
                       final Function<Java20Parser, ParseTree> function1) {
        final var parser = createParser(code);
        final var fileObject = new PathFileObject(
                new FileObject.Kind(".nabu", true),
                Paths.get("SomeClass.class")
        );

        final var visitor = new JavaCompilerVisitor(fileObject, false);
        final ParseTree functionResult = function1.apply(parser);
        return (Tree) functionResult.accept(visitor);
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
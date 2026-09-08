package io.github.potjerodekool.nabu.compiler.frontend.parser.java;

import io.github.potjerodekool.nabu.compiler.frontend.parser.ASTPrinter;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.test.JavaLangTreeAssert;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void testMethodInvocationFollowedByArrayAccess() {
        final var tree = parse("""
                param.typeInfo().getAuxiliaryTypes()[0]
                """, Java20Parser::expression);

        assertNotNull(tree);
        final var expression = (ExpressionTree) tree;
        assertEquals(
                "io.github.potjerodekool.nabu.tree.expression.ArrayAccessExpressionTree",
                expression.getClass().getInterfaces()[0].getName(),
                "Top-level node moet een array-access zijn (M()[i])"
        );
    }

    /**
     * Inventaris van Java-expressievormen die picocli-bron gebruikt.
     * Rapporteert OK (parst zonder exception, non-null) of FAIL (exception /
     * null / stil verlies van operanden). Gebruikt als maat voor 0.4-gaps.
     */
    @Test
    void inventoryJavaExpressionCoverage() {
        final String[] forms = {
                "a || b",
                "a || b || c",
                "a ? b : c",
                "a && b",
                "a && b && c",
                "x = cond ? y : z",
                "a++",
                "++a",
                "a--",
                "--a",
                "a[0]",
                "f()[0]",
                "new int[]{1,2,3}",
                "new String[]{\"a\",\"b\"}",
                "a.b::c",
                "a instanceof B",
                "Foo.class",
                "int[].class",
                "x = a = b",
                "null",
                "\"str\".length()",
                "a.b.c().d",
                "(a + b) * c",
                "a >>> b",
                "a >> b",
                "a << b",
                "count += 1",
                "count -= 1",
                "count *= 2",
                "count /= 2",
                "count %= 2",
                "count &= 1",
                "count |= 1",
                "count ^= 1",
                "list.get(0).intValue()",
                "arr.length",
                "f(1, 2, 3)",
                "new Foo()",
                "new Foo(a, b)",
                "new Foo<>(a)",
                "a < b",
                "a <= b",
                "a >= b",
                "a != b",
                "a == b",
                "a & b",
                "a | b",
                "a ^ b",
                "~a",
                "-a",
                "+a",
                "!a",
                "a % b",
                "a / b",
                "a * b",
                "a + b",
                "a - b",
                "(int) x",
                "(String) y",
        };

        final var failures = new ArrayList<String>();
        final var builder = new StringBuilder();
        for (final var form : forms) {
            final var result = tryParse(form);
            builder.append(result).append("\t").append(form).append("\n");
            if (!"OK".equals(result)) {
                failures.add(form + " -> " + result);
            }
        }
        try {
            Files.writeString(Path.of("C:/Users/evert/AppData/Local/Temp/opencode/expr-inventory.txt"), builder.toString());
        } catch (final IOException ignored) {
        }

        System.out.println("EXPRESSION COVERAGE INVENTORY FAILURES (" + failures.size() + "/" + forms.length + "):");
        failures.forEach(f -> System.out.println("  " + f));
    }

    @Test
    void arrayAccessStacktrace() {
        try {
            parse("a[0]", Java20Parser::expression);
            System.out.println("no exception");
        } catch (final Throwable t) {
            final var cause = t.getCause() != null ? t.getCause() : t;
            final var sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            try {
                Files.writeString(Path.of("C:/Users/evert/AppData/Local/Temp/opencode/arrayaccess-stack.txt"), sw.toString());
            } catch (final IOException ignored) {
            }
        }
    }

    private String tryParse(final String code) {
        try {
            final var tree = parse(code, Java20Parser::expression);
            return tree == null ? "NULL" : "OK";
        } catch (final Throwable t) {
            final var cause = t.getCause() != null ? t.getCause() : t;
            return cause.getClass().getSimpleName();
        }
    }

    @Test
    void ternaryResultIsOnlyLastOperand() {
        final var tree = (ExpressionTree) parse("a ? b : c", Java20Parser::expression);
        System.out.println("TERNARY TREE = " + tree.getClass().getSimpleName());
        final var isBinary = tree instanceof io.github.potjerodekool.nabu.tree.expression.BinaryExpressionTree;
        System.out.println("  isBinaryExpression = " + isBinary);
    }

    @Test
    void statementInventory() {
        final String[] statements = {
                "String x = \"a\" + \"b\";",
                "String[] arr = new String[5];",
                "String[] arr = new String[]{\"a\", \"b\"};",
                "int[] nums = {1, 2, 3};",
                "int[][] matrix = new int[3][3];",
                "for (int i = 0; i < 10; i++) { }",
                "for (String s : list) { }",
                "while (x > 0) { x--; }",
                "do { x--; } while (x > 0);",
                "if (a && b) { } else if (c) { } else { }",
                "switch (x) { case 1: break; case 2: break; default: break; }",
                "switch (x) { case 1 -> y = 1; default -> y = 0; }",
                "try { f(); } catch (Exception e) { }",
                "try (var r = open()) { }",
                "throw new RuntimeException(\"x\");",
                "return a ? b : c;",
                "list.forEach(x -> System.out.println(x));",
                "Runnable r = () -> System.out.println(\"hi\");",
                "Foo f = (Foo) o;",
                "var s = \"\".indent(2).strip();",
                "import java.util.List;",
                "static { System.out.println(\"init\"); }",
                "{ System.out.println(\"instance\"); }",
                "assert x > 0;",
                "label: for (;;) { break label; }",
                "yield 42;",
                "synchronized (lock) { }",
                "class Foo { int x; }",
                "interface I { void m(); }",
                "int m() { return 1; }",
                "int x;",
                "final int x = 1;",
                "String s = switch (x) { case 1 -> \"one\"; default -> \"other\"; };",
        };
        final var builder = new StringBuilder();
        for (final var form : statements) {
            builder.append(tryParseStatement(form)).append("\t").append(form.replace("\n", " ")).append("\n");
        }
        try {
            Files.writeString(Path.of("C:/Users/evert/AppData/Local/Temp/opencode/stmt-inventory.txt"), builder.toString());
        } catch (final IOException ignored) {
        }
        System.out.println("statement inventory written");
    }

    private String tryParseStatement(final String code) {
        try {
            final var tree = parse(code, Java20Parser::compilationUnit);
            return tree == null ? "NULL" : "OK";
        } catch (final Throwable t) {
            final var cause = t.getCause() != null ? t.getCause() : t;
            return "EX:" + cause.getClass().getSimpleName();
        }
    }

    @Test
    void parseCommandLineJavaZeroErrors() throws IOException {
        final var path = Path.of("C:/projects/incurbation/cli-demo/vendor/picocli-src/picocli/CommandLine.java");
        final var source = Files.readString(path);
        final var start = System.currentTimeMillis();
        final var input = CharStreams.fromString(source);
        final var lexer = new Java20Lexer(input);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java20Parser(tokens);
        parser.setBuildParseTree(false);
        parser.compilationUnit();
        final var elapsed = System.currentTimeMillis() - start;
        final var errors = parser.getNumberOfSyntaxErrors();
        System.out.println("CommandLine.java LL parse: " + elapsed + " ms, syntaxErrors=" + errors);
        assertEquals(0, errors);
    }

    @Test
    void assignmentStatementFromAutoComplete() {
        final String[] lines = {
                "sub = functionName.equals(\"_picocli_\" + commandName) ? \"\" : \"sub\";",
                "alias = original.equals(actual) ? \"\" : commandName;",
                "b = result == null ? false : result.contains(\"-\");",
                "len = a > 0 ? a : -a;",
                "usageHelpRequested = line2.toLowerCase().startsWith(\"help\") ? true : false;",
        };
        final var builder = new StringBuilder();
        for (final var line : lines) {
            builder.append(tryParseStatement(line)).append("\t").append(line).append("\n");
        }
        try {
            Files.writeString(Path.of("C:/Users/evert/AppData/Local/Temp/opencode/assign-ter.txt"), builder.toString());
        } catch (final IOException ignored) {
        }
        System.out.println("assignmentStatementFromAutoComplete written");
    }

    @Test
    void primaryIsNotPatternForDottedInitializerInFor() {
        final var tree = parse("""
                class X {
                    void m() {
                        for (Iterator<Pattern> iter = replacements.keySet().iterator(); iter.hasNext(); ) { }
                    }
                }
                """, Java20Parser::compilationUnit);
        assertNotNull(tree);
    }

    @Test
    void interfaceConstantsWithEscapeSequenceInitializers() {
        final String[] fields = {
                "String A = \"plain\";",
                "String B = \"\\t\";",
                "String C = \"\\u001B[\";",
                "String D = \"esc\" + \"ape\";",
                "int E = 1;",
        };
        for (final var field : fields) {
            final var tree = parse("""
                    interface IStyle {
                        %s
                    }
                    """.formatted(field), Java20Parser::compilationUnit);
            assertNotNull(tree);
        }
    }

    @Test
    void arrayCreationWithInitializer() {
        final var tree = (ExpressionTree) parse("new String[]{\"A\", \"B\"}", Java20Parser::primary);
        assertNotNull(tree);
        assertInstanceOf(io.github.potjerodekool.nabu.tree.expression.NewArrayExpression.class, tree);
        final var newArray = (io.github.potjerodekool.nabu.tree.expression.NewArrayExpression) tree;
        assertTrue(newArray.getDimensions().isEmpty());
        assertEquals(2, newArray.getElements().size());
    }

    @Test
    void arrayCreationWithSize() {
        final var tree = (ExpressionTree) parse("new int[10]", Java20Parser::primary);
        assertNotNull(tree);
        assertInstanceOf(io.github.potjerodekool.nabu.tree.expression.NewArrayExpression.class, tree);
        final var newArray = (io.github.potjerodekool.nabu.tree.expression.NewArrayExpression) tree;
        assertEquals(1, newArray.getDimensions().size());
    }

    @Test
    void hexLiteralValue() {
        final var tree = (ExpressionTree) parse("0xFF61", Java20Parser::literal);
        assertNotNull(tree);
        final var literal = (io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree) tree;
        assertEquals(0xFF61, literal.getLiteral());
    }

    @Test
    void sllStaticImportMinimal() {
        final String source = """
                import java.util.List;
                import static java.util.Locale.ENGLISH;
                class A { }
                """;
        final var tokens = new CommonTokenStream(new Java20Lexer(CharStreams.fromString(source)));
        final var parser = new Java20Parser(tokens);
        parser.setBuildParseTree(true);
        parser.getInterpreter().setPredictionMode(org.antlr.v4.runtime.atn.PredictionMode.SLL);
        parser.compilationUnit();
        assertEquals(0, parser.getNumberOfSyntaxErrors());
    }

    @Test
    void fullPicocliCommandLineFileVisitorPasses() throws IOException {
        final var path = Path.of("C:/projects/incurbation/cli-demo/vendor/picocli-src/picocli/CommandLine.java");
        final var source = Files.readString(path);
        final var parser = new Java20Parser(
                new CommonTokenStream(new Java20Lexer(CharStreams.fromString(source))));
        parser.setBuildParseTree(true);
        parser.removeErrorListeners();
        final var compilationUnit = parser.compilationUnit();
        final var fileObject = new PathFileObject(
                new FileObject.Kind(".nabu", true),
                Paths.get("CommandLine.java")
        );
        final var probe = new FieldAccessSuffixProbeVisitor(fileObject);
        try {
            compilationUnit.accept(probe);
        } catch (final Throwable t) {
            System.out.println("VISITOR CRASH on line " + probe.lastPrimaryLine + " -> " + t);
            throw t;
        }
    }

    @Test
    void autoCompleteTernaryLines() {
        final String[] lines = {
                "String sub = functionName.equals(\"_picocli_\" + commandName) ? \"\" : \"sub\";",
                "String alias = original.equals(actual) ? \"\" : commandName;",
                "boolean b = result == null ? false : result.contains(\"-\");",
                "int len = a > 0 ? a : -a;",
                "printf(\"%s%n\", isHelp ? \"help\" : \"run\");",
        };
        final var builder = new StringBuilder();
        for (final var line : lines) {
            builder.append(tryParseStatement(line)).append("\t").append(line).append("\n");
        }
        try {
            Files.writeString(Path.of("C:/Users/evert/AppData/Local/Temp/opencode/ternary-lines.txt"), builder.toString());
        } catch (final IOException ignored) {
        }
        System.out.println("autoCompleteTernaryLines written");
    }

    @Test
    void booleanBinaryShapes() {
        final String[] forms = {"a || b", "a && b", "a || b || c", "a && b && c", "a ? b : c"};
        final var builder = new StringBuilder();
        for (final var form : forms) {
            try {
                final var tree = (ExpressionTree) parse(form, Java20Parser::expression);
                builder.append(form).append("\t").append(tree.getClass().getSimpleName());
                if (tree instanceof io.github.potjerodekool.nabu.tree.expression.BinaryExpressionTree b) {
                    builder.append("\tleft=").append(b.getLeft().getClass().getSimpleName())
                            .append(" right=").append(b.getRight().getClass().getSimpleName())
                            .append(" tag=").append(b.getTag());
                }
                builder.append("\n");
            } catch (final Throwable t) {
                builder.append(form).append("\tEX").append(t.getClass().getSimpleName()).append("\n");
            }
        }
        try {
            Files.writeString(Path.of("C:/Users/evert/AppData/Local/Temp/opencode/logic-shapes.txt"), builder.toString());
        } catch (final IOException ignored) {
        }
        System.out.println(builder);
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

class FieldAccessSuffixProbeVisitor extends JavaCompilerVisitor {

    int lastPrimaryLine = -1;

    FieldAccessSuffixProbeVisitor(final FileObject fileObject) {
        super(fileObject, false);
    }

    @Override
    public Object visitPrimaryNoNewArray(final Java20Parser.PrimaryNoNewArrayContext ctx) {
        lastPrimaryLine = ctx.getStart().getLine();
        return super.visitPrimaryNoNewArray(ctx);
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
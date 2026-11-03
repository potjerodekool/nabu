package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Language features of Java 21 up to 25 in a native executable: pattern matching in switch (expressions and
 * statements, record patterns, guards, null labels), record patterns in instanceof under {@code ?:} and
 * {@code ||}, statements before super(...) and import module.
 */
class Java21To25FeaturesE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void patternsAndNewerSyntaxRunNatively() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                import module java.base;

                public class Main {
                    sealed interface Shape permits Circle, Square, Rect {}
                    record Circle(int r) implements Shape {}
                    record Square(int s) implements Shape {}
                    record Rect(int w, int h) implements Shape {}
                    record Box(Object content) {}

                    static String describe(Shape shape) {
                        return switch (shape) {
                            case Circle c -> "circle " + c.r();
                            case Square s -> "square " + s.s();
                            case Rect(int w, int h) when w == h -> "squarish " + w;
                            case Rect(int w, int h) -> "rect " + (w * h);
                        };
                    }

                    static String kind(Object o) {
                        return switch (o) {
                            case null -> "null";
                            case Integer i when i > 9 -> "big";
                            case Integer i -> "int";
                            case Box(Rect(var w, _)) -> "boxed rect " + w;
                            case Box(Object c) -> "box";
                            default -> "other";
                        };
                    }

                    static String statement(Object o) {
                        String result = "?";
                        switch (o) {
                            case Rect(int w, int h) -> result = "r" + (w + h);
                            case String s -> {
                                result = "s" + s.length();
                            }
                            default -> { }
                        }
                        return result;
                    }

                    static String text(String x) {
                        return switch (x) {
                            case null -> "null";
                            case "a" -> "A";
                            case "b", "c" -> "BC";
                            default -> "other";
                        };
                    }

                    static String npe(Object o) {
                        try {
                            return switch (o) {
                                case String s -> "s";
                                default -> "d";
                            };
                        } catch (NullPointerException e) {
                            return "npe";
                        }
                    }

                    sealed interface Card permits Suit, Joker {}
                    enum Suit implements Card { CLUBS, HEARTS }
                    record Joker() implements Card {}

                    static String card(Card c) {
                        return switch (c) {
                            case Suit.CLUBS -> "c";
                            case Suit.HEARTS -> "h";
                            case Joker j -> "j";
                        };
                    }

                    sealed interface Implicit {}
                    record One() implements Implicit {}
                    record Two() implements Implicit {}

                    static String implicit(Implicit i) {
                        return switch (i) {
                            case One o -> "one";
                            case Two t -> "two";
                        };
                    }

                    static String concatenated(Object o) {
                        return "is " + switch (o) {
                            case Circle c -> "circle";
                            default -> "other";
                        } + "!";
                    }

                    static String asArgument(Object o) {
                        return String.valueOf(switch (o) {
                            case Rect(int w, int h) when w > 5 -> "big";
                            case Rect r -> "small";
                            default -> "other";
                        });
                    }

                    static String insideLambda(Object o) {
                        java.util.function.Function<Object, String> f = x -> switch (x) {
                            case Circle c -> "lc";
                            default -> "lo";
                        };
                        return f.apply(o);
                    }

                    static String shown(Object o) {
                        return String.valueOf(o instanceof Rect(int w, int h) && w > h);
                    }

                    static int sum(Object o) {
                        return o instanceof Rect(int w, int h) ? w + h : -1;
                    }

                    static String negated(Object o) {
                        if (!(o instanceof Rect(int w, int h)) || w > h) {
                            return "no";
                        }
                        return "yes" + (w + h);
                    }

                    static class Base {
                        final int v;
                        Base(int v) {
                            this.v = v;
                        }
                    }

                    static class Derived extends Base {
                        Derived(int x) {
                            if (x < 0) {
                                throw new IllegalArgumentException("neg");
                            }
                            super(x * 2);
                        }
                    }

                    public static void main(String[] args) {
                        System.out.println(describe(new Circle(2)));
                        System.out.println(describe(new Square(3)));
                        System.out.println(describe(new Rect(4, 4)));
                        System.out.println(describe(new Rect(2, 5)));
                        System.out.println(kind(null) + " " + kind(50) + " " + kind(5) + " "
                                + kind(new Box(new Rect(7, 1))) + " " + kind(new Box("x")) + " " + kind("s"));
                        System.out.println(statement(new Rect(1, 2)) + " " + statement("abc") + " " + statement(1));
                        System.out.println(sum(new Rect(2, 3)) + " " + sum("x"));
                        System.out.println(negated(new Rect(1, 2)) + " " + negated(new Rect(3, 2)) + " " + negated("s"));
                        System.out.println(new Derived(21).v);
                        try {
                            new Derived(-1);
                        } catch (IllegalArgumentException e) {
                            System.out.println(e.getMessage());
                        }
                        var list = new ArrayList<String>();
                        list.add("done");
                        System.out.println(list.get(0));
                        System.out.println(text(null) + " " + text("a") + " " + text("c") + " " + text("z") + " "
                                + npe(null) + " " + npe("x"));
                        System.out.println(card(Suit.CLUBS) + " " + card(Suit.HEARTS) + " " + card(new Joker()) + " "
                                + implicit(new One()) + " " + implicit(new Two()));
                        System.out.println(concatenated(new Circle(1)) + " " + asArgument(new Rect(9, 1)) + " "
                                + asArgument(new Rect(1, 1)) + " " + insideLambda(new Circle(1)) + " "
                                + insideLambda("x") + " " + shown(new Rect(3, 1)) + " " + shown(new Rect(1, 3)));
                    }
                }
                """, StandardCharsets.UTF_8);
        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.RELEASE, "25")
                .option(CompilerOption.SYSTEM, new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();
        var diagnostics = new java.util.ArrayList<String>();
        var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        assertEquals(0, compiler.compile(options), String.join("\n", diagnostics));
        Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(NativeToolchain::isExecutableOutput).findFirst().orElseThrow();
        }
        final Path log = tempDir.resolve("run.log");
        Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("the program hangs; output so far:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int exit = proc.exitValue();
        assertEquals("""
                circle 2
                square 3
                squarish 4
                rect 10
                null big int boxed rect 7 box other
                r3 s3 ?
                5 -1
                yes3 no no
                42
                neg
                done
                null A BC other npe s
                c h j one two
                is circle! big small lc lo true false
                """, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}

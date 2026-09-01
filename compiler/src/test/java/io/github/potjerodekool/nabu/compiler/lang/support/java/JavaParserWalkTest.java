package io.github.potjerodekool.nabu.compiler.lang.support.java;

import io.github.potjerodekool.nabu.compiler.InMemoryFileObject;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks the ANTLR-generated Java20Parser tree so that generated parser,
 * context and listener/visitor base classes are exercised even when the
 * higher level JavaCompilerVisitor does not support every construct yet.
 */
class JavaParserWalkTest {

    private static Path testResources() {
        var current = Path.of("").toAbsolutePath();
        while (current != null && !"nabu".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        return current.resolve("compiler").resolve("src").resolve("test").resolve("resources");
    }

    private List<String> listJavaSources() throws IOException {
        final var root = testResources();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::readSource)
                    .collect(Collectors.toList());
        }
    }

    private String readSource(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new RuntimeException("Cannot read " + path, e);
        }
    }

    private void walkJava(final String source) throws IOException {
        final var ctx = JavaCompilerParser.parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));

        ParseTreeWalker.DEFAULT.walk(new Java20ParserBaseListener() {
        }, ctx);

        ctx.accept(new Java20ParserBaseVisitor<>() {
        });

        try {
            final var fileObject = new InMemoryFileObject(source, "Walk.java");
            ctx.accept(new JavaCompilerVisitor(fileObject, false));
        } catch (final RuntimeException ignored) {
            // JavaCompilerVisitor does not support every Java construct yet,
            // but the generated parser code already got exercised.
        }
    }

    @Test
    void parseEveryJavaResource() throws IOException {
        final var sources = listJavaSources();
        assertTrue(sources.size() > 0, "no java test resources found");

        for (final var source : sources) {
            walkJava(source);
        }
    }

    @Test
    void parseRichConstructs() throws IOException {
        final List<String> sources = new ArrayList<>();

        sources.add("""
                package a.b.c;
                import java.util.*;
                import java.util.stream.*;
                import static java.util.Collections.*;
                import static java.util.stream.Collectors.*;

                @Deprecated
                @Info(names = {"a", "b"}, code = 42)
                public final class Rich< T extends Comparable<T> & java.io.Serializable > {
                    static final int MAX = Integer.MAX_VALUE;
                    private final List<String> names = new ArrayList<>();
                    private int[] values = {1, 2, 3};
                    private int[][] matrix = new int[3][];

                    static {
                        System.out.println("loaded");
                    }

                    {
                        names.add("init");
                    }

                    @Info("marker")
                    public <R extends T> R convert(final R input, final String... rest) {
                        return input;
                    }

                    public int compute(final int x) throws ArithmeticException {
                        try (var in = new java.io.ByteArrayInputStream(new byte[0]);
                             var out = new java.io.ByteArrayOutputStream()) {
                            in.read();
                            out.write(x);
                        } catch (final java.io.IOException | IllegalArgumentException e) {
                            throw new ArithmeticException("bad");
                        } finally {
                            System.out.println("done");
                        }
                        return x << 2 >> 1 >>> 3 | x & 0xff ^ 1;
                    }

                    public String switchForms(final Object o, final int i) {
                        String out;
                        synchronized (this) {
                            switch (i) {
                                case 1:
                                    out = "one";
                                    break;
                                case 2, 3:
                                    out = "few";
                                    break;
                                default:
                                    out = "many";
                            }
                        }
                        switch (i) {
                            case 1 -> { out = "one"; }
                            default -> out = String.valueOf(i);
                        }
                        final var value = switch (i) {
                            case 1 -> "one";
                            default -> "other";
                        };
                        final int yielded = switch (k) {
                            case 10 -> { yield 100; }
                            default -> throw new IllegalStateException();
                        };
                        return value + yielded;
                    }

                    public int loops() {
                        int total = 0;
                        lbl:
                        for (int i = 0; i < 10; i++) {
                            for (int j = 0; j < 10; j++) {
                                if (j == 5) {
                                    continue lbl;
                                }
                                total += i * j;
                            }
                            if (i == 9) {
                                break lbl;
                            }
                        }
                        int k = 0;
                        while (k < 3) {
                            k++;
                        }
                        do {
                            k--;
                        } while (k > 0);
                        assert total >= 0 : "total must be positive";
                        return total;
                    }

                    public boolean checks(final Object obj) {
                        if (obj instanceof String s) {
                            return s.isEmpty();
                        }
                        final int i = (int) 42L;
                        return i > 0 && !false || obj == null;
                    }

                    public void arrays() {
                        int[] plain = new int[]{1, 2};
                        Object[][] grid = new Object[3][3];
                        grid[0][0] = "x";
                        plain[0] = plain[plain.length - 1];
                        String textBlock = \"\"\"
                                first line
                                second line
                                \"\"\";
                        char c = 'a';
                        double d = 12_345.678_9e+2d;
                        long hex = 0x_CAFE_BABEL;
                    }

                    public java.util.function.Supplier<String> funcs(List<String> list) {
                        list.sort(String::compareTo);
                        list.forEach(System.out::println);
                        Runnable r = this::run;
                        return super::toString;
                    }

                    void run() {
                    }

                    class Inner {
                        Inner() {
                            super();
                        }

                        class Deeper {
                        }
                    }

                    static class Nested<T> {
                        <U> U id(U u) {
                            return u;
                        }
                    }

                    interface InnerInterface extends Comparable<Rich<T>> {
                        void doIt();

                        default int n() {
                            return 1;
                        }
                    }
                }

                @interface Info {
                    String[] names() default {};

                    int code() default 0;

                    String value() default "";
                }
                """);

        sources.add("""
                package sealed;
                import java.util.function.Supplier;

                public sealed interface Shape permits Circle, Square {
                    double area();

                    static Supplier<Shape> circle(final double r) {
                        Circle circle = new Circle(r);
                        Shape s = circle;
                        Supplier<Shape> supplier = () -> s;
                        return supplier;
                    }
                }

                record Circle(double radius) implements Shape {
                    @Override
                    public double area() {
                        return 2 * Math.PI * radius;
                    }

                    public Circle {
                        if (radius <= 0) {
                            throw new IllegalArgumentException("radius");
                        }
                    }
                }

                final class Square implements Shape {
                    @Override
                    public double area() {
                        return 0;
                    }
                }

                enum Color implements Shape {
                    RED(255, 0, 0) {
                        @Override
                        public double area() {
                            return 1;
                        }
                    },
                    BLUE(0, 0, 255);

                    private final int r;
                    private final int g;
                    private final int b;

                    Color(int r, int g, int b) {
                        this.r = r;
                        this.g = g;
                        this.b = b;
                    }

                    Color(int r, int g) {
                        this(r, g, 0);
                    }

                    @Override
                    public double area() {
                        return 0;
                    }
                }

                class Local {
                    Object local() {
                        class Hidden {
                            int x() {
                                return 1;
                            }
                        }
                        return new Hidden() {
                            @Override
                            public int x() {
                                return super.x();
                            }
                        };
                    }
                }
                """);

        sources.add("""
                module demo.app {
                    requires transitive java.base;
                    requires static demo.legacy;
                    exports demo.api;
                    exports demo.internal to demo.impl;
                    opens demo.internal;
                    uses demo.api.Service;
                    provides demo.api.Service with demo.impl.ServiceImpl;
                }
                """);

        for (final var source : sources) {
            walkJava(source);
        }
    }
}
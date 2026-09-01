package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.tools.diagnostic.DiagnosticListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class NabuResolverReproTest {

    @TempDir
    Path tempDir;

    private int compile(final String className, final String source) throws IOException {
        final var sourceDir = Files.createDirectories(tempDir.resolve("src"));
        final var pkgDir = Files.createDirectories(sourceDir.resolve("io/github/potjerodekool"));
        Files.writeString(pkgDir.resolve(className + ".java"), source);

        final var compiler = new NabuCompiler();
        final var diags = new java.util.ArrayList<String>();
        compiler.setListener(new DiagnosticListener() {
            @Override
            public void report(final Diagnostic diagnostic) {
                diags.add(diagnostic.getKind() + " " + diagnostic.getMessage(java.util.Locale.ROOT)
                        + " @" + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber());
            }
        });

        var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, sourceDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, tempDir.resolve("out").toString())
                .build();
        final var result = compiler.compile(options);

        System.out.println("== compile result=" + result);
        diags.forEach(d -> System.out.println("   DIAG: " + d));
        return result;
    }

    @Test
    void starImport() throws IOException {
        final var source = """
                package io.github.potjerodekool;

                import java.util.*;

                public class StarImport {
                    private List<String> names = new ArrayList<>(new HashSet<String>());
                }
                """;
        compile("StarImport", source);
    }

    @Test
    void singleImport() throws IOException {
        final var source = """
                package io.github.potjerodekool;

                import java.util.Objects;
                import java.util.List;

                public class SingleImport {
                    private Integer id;
                    public boolean eq(final SingleImport other) {
                        return Objects.equals(id, other.id);
                    }
                }
                """;
        compile("SingleImport", source);
    }

    @Test
    void patternVar() throws IOException {
        final var source = """
                package io.github.potjerodekool;

                public class PatternVar {
                    private Integer id;
                    public boolean eq(final Object obj) {
                        if (obj instanceof PatternVar other) {
                            return other.id == id;
                        }
                        return false;
                    }
                }
                """;
        compile("PatternVar", source);
    }

    @Test
    void plainClass() throws IOException {
        final var source = """
                package io.github.potjerodekool;

                public class Plain {
                    private String name;
                    public String greet(final String x) {
                        return name + x;
                    }
                }
                """;
        compile("Plain", source);
    }

    @Test
    void paramFieldAccess() throws IOException {
        final var source = """
                package io.github.potjerodekool;

                public class ParamField {
                    private Integer id;
                    public Integer getId() {
                        return id;
                    }
                    public Integer other(final ParamField other) {
                        return other.id;
                    }
                }
                """;
        compile("ParamField", source);
    }

    @Test
    void thisFieldAccess() throws IOException {
        final var source = """
                package io.github.potjerodekool;

                public class ThisField {
                    private Integer id;
                    public Integer other(final ThisField ignored) {
                        return this.id;
                    }
                }
                """;
        compile("ThisField", source);
    }

    @Test
    void patternVarFieldAccess() throws IOException {
        final var source = """
                package io.github.potjerodekool;

                public class PatternField {
                    private Integer id;
                    public boolean eq(final Object obj) {
                        if (obj instanceof PatternField other) {
                            return other.id == id;
                        }
                        return false;
                    }
                }
                """;
        compile("PatternField", source);
    }

    @Test
    void enumOnly() throws IOException {
        final var source = """
                package io.github.potjerodekool;

                public enum Color {
                    RED("red"),
                    GREEN("green");

                    private final String value;

                    Color(final String value) {
                        this.value = value;
                    }
                }
                """;
        compile("Color", source);
    }

    @Test
    void arrayTypeAsGenericTypeArgument() throws IOException {
        final var source = """
                package io.github.potjerodekool;

                import java.util.Map;

                public class ArrayTypeArg {
                    public void test() {
                        Map<String, byte[]> data = null;
                    }
                }
                """;
        compile("ArrayTypeArg", source);
    }
}

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
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressietests op de varargs-lowering van de frontend (JLS 15.12.4.4).
 *
 * <p>{@code Va.f(int first, int... rest)} heeft in de IR twee parameters
 * ({@code i32} plus array-pointer). De call-site {@code f(1, 2, 3, 4)} hoort dus
 * een array te construeren en twee argumenten mee te geven. Voor de fix werden
 * de varargs een keer als losse argumenten na de array uitgezonden en kreeg de
 * callee vier argumenten bij twee parameters, wat LLVM afkeurde met
 * "Incorrect number of arguments passed to called function".
 *
 * <p>Getest worden de primitieve en de referentie-variant (een
 * {@code String...}-parameter wordt door een ander {@code ArrayType}
 * gerepresenteerd dan {@code int...}) en de lege varargs-call, die volgens de
 * JLS alsnog een lege array moet meedragen.
 *
 * <p>Deze test leest de gegenereerde {@code .ll}; linken is niet nodig en zou
 * alleen afhangen van runtime-symbolen als {@code System.exit}.
 */
class VarargsProbeIT {

    @TempDir
    Path tempDir;

    private record Case(String callee, String call, String fn, int expectedArgs) {}

    private void assertCallArity(Case c, int expectedArgs) throws Exception {
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }

        Files.writeString(tempDir.resolve("Va.java"),
                "public class Va {\n" +
                "    public static int " + c.callee() + " {\n" +
                "        return 1;\n" +
                "    }\n" +
                "}\n",
                StandardCharsets.UTF_8);

        Path src = tempDir.resolve("Main.java");
        Files.writeString(src,
                "public class Main {\n" +
                "    public static void main(String[] args) {\n" +
                "        System.exit(Va." + c.call() + ");\n" +
                "    }\n" +
                "}\n",
                StandardCharsets.UTF_8);

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());

        Path out = tempDir.resolve("out-" + c.fn());
        Files.createDirectories(out);

        var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, src.getParent().toAbsolutePath().toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toAbsolutePath().toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.SYSTEM,
                        new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();

        var diagnostics = new ArrayList<String>();
        var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(
                d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        compiler.compile(options);

        Path ll;
        try (var walk = Files.walk(out)) {
            ll = walk.filter(p -> p.getFileName().toString().endsWith(".ll"))
                    .findFirst().orElse(null);
        }
        assertTrue(ll != null, "Geen .ll geproduceerd in " + out);

        String text = Files.readString(ll);
        var m = java.util.regex.Pattern
                .compile("call i32 @Va_" + c.fn() + "\\(([^)]*)\\)").matcher(text);
        assertTrue(m.find(), "Geen call i32 @Va_" + c.fn() + "(...) in " + ll
                + "\ndiagnostics:\n" + String.join("\n", diagnostics));

        String[] args = m.group(1).trim().isEmpty()
                ? new String[0] : m.group(1).split(",");
        assertEquals(expectedArgs, args.length,
                "De varargs-call hoort " + expectedArgs + " argumenten te geven, maar kreeg er "
                        + args.length + ": " + m.group(0)
                        + "\ndiagnostics:\n" + String.join("\n", diagnostics));
    }

    @Test
    void primitiveVarargsArePackedIntoTheArrayParameter() throws Exception {
        assertCallArity(
                new Case("f(int first, int... rest)", "f(1, 2, 3, 4)", "f", 2), 2);
    }

    @Test
    void referenceVarargsArePackedIntoTheArrayParameter() throws Exception {
        assertCallArity(
                new Case("g(String first, String... rest)", "g(\"x\", \"a\", \"b\", \"c\")", "g", 2), 2);
    }

    @Test
    void emptyVarargsCallStillPassesAnEmptyArray() throws Exception {
        assertCallArity(
                new Case("h(int first, int... rest)", "h(1)", "h", 2), 2);
    }
}
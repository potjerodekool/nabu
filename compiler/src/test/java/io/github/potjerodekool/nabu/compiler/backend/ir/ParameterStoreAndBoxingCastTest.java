package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.tools.Compiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressietests voor twee frontend-fouten die de LLVM-validator dwongen om de
 * hele picocli-batch te weigeren.
 *
 * <ol>
 *   <li>Een toewijzing aan een method-parameter emitteerde een
 *       {@code store <T> waarde, <T> parameter}: de parameter was in de scope
 *       aan de waarde-temp gebonden in plaats van aan een slot. Parameters zijn
 *       toewijsbaar (JLS 8.3.1) en hebben dus een alloca-slot nodig.</li>
 *   <li>Een cast van een primitief naar een referentietype
 *       ({@code (Object) someBoolean}) werd als kale cast geemitteerd, wat neer
 *       komt op {@code bitcast i1 to ptr}. Dat is een boxing-cast (JLS 5.1.7).</li>
 * </ol>
 *
 * <p>Beide asserts zijn backend-onafhankelijk: ze controleren de IR-invariante
 * in plaats van een validatorfout, zodat ook de ASM-backend ze zou vangen.
 */
class ParameterStoreAndBoxingCastTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------
    // 1. Toewijzing aan een parameter
    // -------------------------------------------------------

    @Test
    void assignmentToParameterStoresIntoASlot() throws Exception {
        final var functions = compile("package mini;\n"
                + "public class Mini {\n"
                + "    static int bump(boolean negated, int consumed) {\n"
                + "        negated = true;\n"
                + "        consumed = consumed + 1;\n"
                + "        return negated ? consumed : -consumed;\n"
                + "    }\n"
                + "}\n");

        final var fn = findFunction(functions, "Mini_bump");

        // De parameter is toewijsbaar en heeft dus een entry-alloca gekregen.
        final var allocas = instructions(fn, IRInstruction.Alloca.class)
                .stream()
                .map(a -> IRValue.nameOf(a.result()))
                .toList();
        assertTrue(allocas.contains("%negated.arg.ptr"),
                "parameter negated heeft geen slot, wel: " + allocas);
        assertTrue(allocas.contains("%consumed.arg.ptr"),
                "parameter consumed heeft geen slot, wel: " + allocas);

        // Geen enkele Store mag een niet-pointer als adres hebben.
        assertNoStoreToNonPointer(fn);
    }

    @Test
    void assignmentToReferenceParameterStoresIntoASlot() throws Exception {
        // Ook een referentie-parameter: zonder slot zou dit
        // `store ptr %nieuw, ptr %value` worden, wat formeel geldige IR is maar
        // naar het aangewezen object schrijft in plaats van de parameter te
        // herbinden.
        final var functions = compile("package mini;\n"
                + "public class Mini {\n"
                + "    static int swap(String value) {\n"
                + "        value = \"anders\";\n"
                + "        return value.length();\n"
                + "    }\n"
                + "}\n");

        final var fn = findFunction(functions, "Mini_swap");
        assertNoStoreToNonPointer(fn);
        assertStoresTargetSlots(fn);
    }

    @Test
    void parameterSlotIsReadBackAfterAssignment() throws Exception {
        // De lezing na de toewijzing moet de nieuwe waarde zien: de parameter
        // verwijst naar het slot, dus de SSA-vorm leest de gepromoveerde versie.
        final var functions = compile("package mini;\n"
                + "public class Mini {\n"
                + "    static boolean flip(boolean flag) {\n"
                + "        flag = !flag;\n"
                + "        return flag;\n"
                + "    }\n"
                + "}\n");

        final var fn = findFunction(functions, "Mini_flip");
        assertNoStoreToNonPointer(fn);

        final var allocas = instructions(fn, IRInstruction.Alloca.class)
                .stream()
                .map(a -> IRValue.nameOf(a.result()))
                .toList();
        assertTrue(allocas.contains("%flag.arg.ptr"),
                "parameter-slot niet als alloca aanwezig, wel: " + allocas);
    }

    // -------------------------------------------------------
    // 2. Cast van primitief naar referentie
    // -------------------------------------------------------

    @Test
    void castOfPrimitiveToObjectBoxesInsteadOfBitcasting() throws Exception {
        // 1:1-vorm van picocli CommandLine$Interpreter.consumeArguments
        // (`Collections.singletonList((Object) booleanDefault)`).
        final var functions = compile("package mini;\n"
                + "import java.util.Collections;\n"
                + "import java.util.List;\n"
                + "public class Mini {\n"
                + "    static List<Object> wrap(boolean booleanDefault) {\n"
                + "        if (booleanDefault) {\n"
                + "            return Collections.singletonList((Object) booleanDefault);\n"
                + "        }\n"
                + "        return Collections.singletonList((Object) !booleanDefault);\n"
                + "    }\n"
                + "}\n");

        final var fn = findFunction(functions, "Mini_wrap");

        // Geen enkele Cast van een primitief naar een Ptr: dat is de bitcast.
        for (final var block : fn.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.Cast cast) {
                    assertFalse(isPrimitive(cast.source().type())
                                    && cast.targetType() instanceof IRType.Ptr,
                            "primitief -> Ptr cast gevonden (bitcast): "
                                    + cast.source().type() + " -> "
                                    + cast.targetType());
                }
            }
        }

        // ... in plaats daarvan een boxing-call.
        final var calls = instructions(fn, IRInstruction.Call.class)
                .stream()
                .map(IRInstruction.Call::function)
                .toList();
        assertTrue(calls.contains("java_lang_Boolean_valueOf"),
                "geen boxing-call gevonden, wel: " + calls);
    }

    @Test
    void castOfPrimitiveToConcreteWrapperUsesThatWrapper() throws Exception {
        final var functions = compile("package mini;\n"
                + "public class Mini {\n"
                + "    static Object asInteger(int count) {\n"
                + "        return (Integer) count;\n"
                + "    }\n"
                + "}\n");

        final var calls = instructions(findFunction(functions, "Mini_asInteger"),
                        IRInstruction.Call.class)
                .stream()
                .map(IRInstruction.Call::function)
                .toList();
        assertTrue(calls.contains("java_lang_Integer_valueOf"),
                "Integer-boxing verwacht, wel: " + calls);
    }

    @Test
    void narrowingCastBetweenPrimitivesIsStillACast() throws Exception {
        // Een cast tussen primitieven blijft een echte Cast; de boxing-fix mag
        // daar niets aan veranderen.
        final var functions = compile("package mini;\n"
                + "public class Mini {\n"
                + "    static int narrow(long big) {\n"
                + "        return (int) big;\n"
                + "    }\n"
                + "}\n");

        assertFalse(instructions(findFunction(functions, "Mini_narrow"),
                        IRInstruction.Call.class)
                .stream()
                .anyMatch(c -> c.function().contains("_valueOf")),
                "(int) long mag niet boxen");
    }

    // -------------------------------------------------------
    // Harness
    // -------------------------------------------------------

    /**
     * Elke Store in de functie moet naar een echt slot schrijven. Een adres dat
     * geen {@link IRType.Ptr} is, betekent dat er naar een waarde in plaats van
     * naar een slot gestore'd wordt — precies de fout die de LLVM-validator als
     * "Store operand must be a pointer" afkeelde.
     */
    private static void assertNoStoreToNonPointer(final IRFunction fn) {
        for (final var block : fn.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.Store store) {
                    final var ptr = store.ptr();
                    assertTrue(ptr.type() instanceof IRType.Ptr,
                            "Store-adres is geen pointer (" + ptr.type() + "): "
                                    + store + " in " + fn.name + "/" + block.label());
                }
            }
        }
    }

    /**
     * Assert dat iedere Store naar een alloca-slot schrijft en niet naar een
     * willekeurige waarde-temp.
     *
     * <p>Nodig voor referentietypes: daar is {@code store ptr %nieuw, ptr %value}
     * formeel geldige IR (het adres is immers een pointer), maar semantisch
     * schrijft het naar het object dat de parameter aanwijst in plaats van de
     * parameter zelf te herbinden. De LLVM-validator ziet dat niet, dus de
     * type-check alleen is hier niet genoeg.
     */
    private static void assertStoresTargetSlots(final IRFunction fn) {
        final var slots = instructions(fn, IRInstruction.Alloca.class)
                .stream()
                .map(a -> a.result())
                .collect(Collectors.toSet());

        final var stores = instructions(fn, IRInstruction.Store.class);
        assertFalse(stores.isEmpty(), "geen Store gevonden in " + fn.name);

        for (final var store : stores) {
            assertTrue(slots.contains(store.ptr()),
                    "Store schrijft niet naar een slot (" + store.ptr()
                            + "): " + store + " in " + fn.name + "/" + fn.blocks()
                            .stream().filter(b -> b.instructions().contains(store))
                            .map(IRBasicBlock::label).findFirst().orElse("?"));
        }
    }

    private static boolean isPrimitive(final IRType type) {
        return type instanceof IRType.Int
                || type instanceof IRType.Float
                || type instanceof IRType.Bool;
    }

    private static <T extends IRInstruction> List<T> instructions(final IRFunction fn,
                                                                  final Class<T> kind) {
        final var out = new ArrayList<T>();
        for (final var block : fn.blocks()) {
            for (final var instr : block.instructions()) {
                if (kind.isInstance(instr)) {
                    out.add(kind.cast(instr));
                }
            }
        }
        return out;
    }

    private static IRFunction findFunction(final List<IRFunction> functions,
                                           final String suffix) {
        return functions.stream()
                .filter(f -> !f.isExternal())
                .filter(f -> f.name.endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("functie niet gevonden: "
                        + suffix + ", wel: "
                        + functions.stream().map(f -> f.name).toList()));
    }

    /** Draait de frontend tot en met IR op een in-memory bronbestand. */
    private List<IRFunction> compile(final String source) throws Exception {
        final var file = tempDir.resolve("src/mini/Mini.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);

        final var context = context();
        final FileObject fo = new PathFileObject(new FileObject.Kind("java", true), file);
        final String text;
        try (var in = fo.openInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        final var parser = new io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser(
                new CommonTokenStream(
                        new io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer(
                                CharStreams.fromString(text))));
        final var cu = (io.github.potjerodekool.nabu.tree.CompilationUnit)
                parser.compilationUnit().accept(
                        new io.github.potjerodekool.nabu.compiler.lang.support.java
                                .JavaCompilerVisitor(fo, false));

        io.github.potjerodekool.nabu.compiler.impl.EnterPhase.enterPhase(cu, context);
        io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase
                .resolvePhase(cu, context);
        io.github.potjerodekool.nabu.compiler.backend.lower.Lower.lower(cu, context);

        final var visitor = new IrGeneratingVisitor();
        visitor.acceptTree(cu, null);

        final var out = new ArrayList<IRFunction>();
        for (final var module : visitor.getModules()) {
            out.addAll(module.functions());
        }
        assertFalse(out.isEmpty(), "geen functies gegenereerd");
        return out;
    }

    private static CompilerContextImpl context() throws Exception {
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }
        final var rootPath = new File(root, "compiler").getAbsolutePath();
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, rootPath + "/src/test/resources/classes")
                .build();
        final var compiler = java.util.ServiceLoader.load(Compiler.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No compiler"));
        return (CompilerContextImpl) compiler.configure(options);
    }
}
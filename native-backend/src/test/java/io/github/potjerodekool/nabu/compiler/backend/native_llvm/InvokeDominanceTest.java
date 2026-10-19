package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.backend.ir.Optimizer;
import io.github.potjerodekool.nabu.backend.ir.SsaBuilder;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.impl.EnterPhase;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase;
import io.github.potjerodekool.nabu.tools.Compiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dominantie van {@code invoke}-resultaten bij try/catch-merges.
 *
 * <p>Een invoke-instructie is een terminator: het resultaat bestaat alleen in
 * de continuation. Het catch-blok en de continuation komen samen in het
 * try-end-blok, dus als dat blok het invoke-resultaat DIRECT zou gebruiken,
 * schendt dat de dominantieregel en weigert de LLVM-validator met
 * "Instruction does not dominate all uses!".
 *
 * <p>De backend voorkomt dat door élke phi aan een slot in het entry-blok te
 * hangen; die alloca domineert alle paden. Deze test legt dat vast én legt de
 * slotgrootte vast: de slot moet groot genoeg zijn voor de waarde die erin
 * past (8 bytes voor een referentie), niet voor het pointee-element.
 */
class InvokeDominanceTest {

    @TempDir
    Path tempDir;

    private static CompilerContextImpl context() throws Exception {
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }
        String rootPath = new File(root, "compiler").getAbsolutePath();
        CompilerOptions options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, rootPath + "/src/test/resources/classes")
                .build();
        Compiler compiler = java.util.ServiceLoader.load(Compiler.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("No compiler"));
        return (CompilerContextImpl) compiler.configure(options);
    }

    private IRModule irOf(String source) throws Exception {
        Path tmp = Files.createTempFile("DOM", ".java");
        Files.writeString(tmp, source, StandardCharsets.UTF_8);

        FileObject fo = new PathFileObject(new FileObject.Kind("java", true), tmp);
        try (var in = fo.openInputStream()) {
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var parser = new Java20Parser(new org.antlr.v4.runtime.CommonTokenStream(
                    new Java20Lexer(CharStreams.fromString(text))));
            var cu = (io.github.potjerodekool.nabu.tree.CompilationUnit)
                    parser.compilationUnit().accept(new JavaCompilerVisitor(fo, false));

            EnterPhase.enterPhase(cu, context());
            ResolverPhase.resolvePhase(cu, context());
            io.github.potjerodekool.nabu.compiler.backend.lower.Lower.lower(cu, context());

            IrGeneratingVisitor visitor = new IrGeneratingVisitor();
            visitor.acceptTree(cu, null);
            // Geneste klassen (Mini$Provider) hebben geen brongebruik in de
            // asserties; neem de hoofdklasse.
            IRModule module = visitor.getModules().stream()
                    .filter(m -> !m.name.contains("$"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Geen klasmodule in " + visitor.getModules()));
            SsaBuilder.run(module);
            return Optimizer.optimize(module);
        }
    }

    /**
     * Compileert naar .ll; {@code emitIRText} valideert de volledige module, dus
     * elke dominantie- of typefout in dit pad faalt de test.
     */
    private String compileToLl(String tag, String source) throws Exception {
        IRModule module = irOf(source);
        Path ll = tempDir.resolve(tag + ".ll");
        new NativeLLVMBackend().emitIRText(module, CompileOptions.debug(), ll);
        return Files.readString(ll);
    }

    @Test
    void objectCallResultUsedAfterTryCatchMerge() throws Exception {
        String ll = compileToLl("after", """
                package mini;
                public class Mini {
                    interface Provider { Object get(); }
                    Object run(Provider p) {
                        Object r;
                        try {
                            r = p.get();
                        } catch (Exception e) {
                            r = null;
                        }
                        return r;
                    }
                }
                """);

        assertTrue(ll.contains("invoke ptr @"), "Verwacht een invoke in de try-regio:\n" + ll);
        assertTrue(ll.contains("unwind label"), "Verwacht een unwind-pad:\n" + ll);
        assertTrue(ll.contains("landingpad"), "Verwacht een landingpad:\n" + ll);
    }

    @Test
    void intCallResultUsedAfterTryCatchMerge() throws Exception {
        String ll = compileToLl("int", """
                package mini;
                public class Mini {
                    interface Gen { int code(); }
                    int run(Gen g) {
                        int r = 0;
                        try {
                            r = g.code();
                        } catch (Exception e) {
                            r = -1;
                        }
                        return r + 1;
                    }
                }
                """);

        assertTrue(ll.contains("invoke i32 @") || ll.contains("invoke i32 %.ifnptr"),
                "Verwacht een invoke met int-resultaat:\n" + ll);
        assertTrue(ll.contains("unwind label"), "Verwacht een unwind-pad:\n" + ll);
    }

    @Test
    void callResultStoredIntoFieldAfterTryCatchMerge() throws Exception {
        String ll = compileToLl("field", """
                package mini;
                public class Mini {
                    interface Provider { Object get(); }
                    Object cached;
                    Object run(Provider p) {
                        try {
                            cached = p.get();
                        } catch (Exception e) {
                            cached = null;
                        }
                        return cached;
                    }
                }
                """);

        assertTrue(ll.contains("invoke ptr @"), "Verwacht een invoke in de try-regio:\n" + ll);
        assertTrue(ll.contains("unwind label"), "Verwacht een unwind-pad:\n" + ll);
    }

    /**
     * De phi-slotgrootte: een slot voor een referentie is 8 bytes (een pointer),
     * niet het pointee-element. Was de slot een `alloca i8` (1 byte) dan zou de
     * achtbyte `store ptr` de aangrenzende stackslot wegschrijven. (A variable
     * assigned in a catch block is not promoted, so the phi comes from an
     * if/else here.)
     */
    @Test
    void referencePhiSlotsArePointerSized() throws Exception {
        String ll = compileToLl("slotsize", """
                package mini;
                public class MiniSlots {
                    interface Provider { Object get(); }
                    Object run(Provider p, boolean b) {
                        Object r;
                        if (b) {
                            r = p.get();
                        } else {
                            r = null;
                        }
                        return r;
                    }
                }
                """);

        var slots = new java.util.ArrayList<String>();
        for (var line : ll.split("\n")) {
            String t = line.trim();
            if (t.contains("= alloca") && t.contains("phi")) {
                slots.add(t);
            }
        }
        assertFalse(slots.isEmpty(), "Verwacht phi-slot-allocas:\n" + ll);
        for (String slot : slots) {
            assertTrue(slot.contains("alloca ptr"),
                    "Phi-slot moet groot genoeg zijn voor de waarde zelf, "
                            + "niet voor het pointee-element, maar vond: " + slot + "\n" + ll);
            assertFalse(slot.contains("alloca i8"),
                    "1-byte slot voor wat een 8-byte store krijgt:\n" + ll);
        }
    }
}
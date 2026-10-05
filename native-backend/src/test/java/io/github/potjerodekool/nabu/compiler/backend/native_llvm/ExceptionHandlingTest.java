package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import static org.junit.jupiter.api.Assertions.*;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Fase 3.1 (backend): exception handling in de native LLVM-backend.
 *
 * Hier: een {@code Throw}-instructie binnen een try-regio moet als
 * {@code invoke} naar {@code @nabu_throw} worden geëmit (zodat de Itanium
 * unwinder het richting de catch-handler leidt), gevolgd door {@code
 * unreachable}. Buiten een try-regio is het een gewone {@code call} gevolgd
 * door {@code unreachable}. Ook testen we dat het throw-object (nu nog als
 * placeholder) niet crasht wanneer de frontend het resultaat (nog) niet vult.
 */
class ExceptionHandlingTest {

    @TempDir
    Path tempDir;

    private static final IRType EXC_TYPE = new IRType.Ptr(IRType.I8, "Ltest/Exception;");

    private String compileAndReadLl(IRModule module, CompileOptions opts) throws Exception {
        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(module, opts, out);
        Path ll = tempDir.resolve("out.ll");
        assertTrue(ll.toFile().exists(), ".ll bestand niet aangemaakt");
        return Files.readString(ll);
    }

    private IRModule buildThrowingModule(boolean inTry) {
        IRBuilder b = new IRBuilder("test/Main");
        b.beginFunction("main", IRType.VOID, List.of(), 0L, false);

        if (inTry) {
            IRBasicBlock entry = b.currentBlock();
            IRBasicBlock tryBody = b.beginBlock("try.body");
            IRBasicBlock tryEnd = b.beginBlock("try.end");
            IRBasicBlock handler = b.beginBlock("try.catch.0");

            b.setCurrentBlock(entry);
            b.emitBranch(tryBody);

            // try.body: gooi een exception-object (result==null -> placeholder-path)
            b.setCurrentBlock(tryBody);
            b.emitThrow(EXC_TYPE);

            // Handler-blok: TryCatchRegion-metadata (start<->end beschermd door
            // handler) gevolgd door een return.
            b.setCurrentBlock(handler);
            handler.add(0, new IRInstruction.TryCatchRegion(
                    tryBody.label(),
                    tryEnd.label(),
                    handler.label(),
                    null,
                    b.currentLocation()));
            b.emitReturn(null);

            // try.end: continuatie-blok na het try-regio (hier onbereikbaar).
            b.setCurrentBlock(tryEnd);
            b.emitReturn(null);
        } else {
            IRBasicBlock entry = b.currentBlock();
            IRBasicBlock body = b.beginBlock("body");
            b.setCurrentBlock(entry);
            b.emitBranch(body);
            b.setCurrentBlock(body);
            b.emitThrow(EXC_TYPE);
        }

        b.endFunction();
        return b.build();
    }

    @Test
    void throwInsideTryUsesInvoke() throws Exception {
        String ll = compileAndReadLl(buildThrowingModule(true), CompileOptions.defaults());

        // Throw binnen een try-regio moet een invoke naar @nabu_throw zijn,
        // zodat de unwinder richting de catch-handler kan leiden.
        assertTrue(ll.contains("invoke void @nabu_throw("),
                "Verwacht invoke naar @nabu_throw binnen try-regio:\n" + ll);
        // Na een noreturn throw moet een unreachable staan.
        assertTrue(ll.contains("unreachable"),
                "Verwacht unreachable na de throw:\n" + ll);
        // De catch-handler moet een landingpad hebben.
        assertTrue(ll.contains("landingpad"),
                "Verwacht een landingpad in de catch-handler:\n" + ll);
        // En geen gewone (non-invoke) call naar @nabu_throw.
        assertFalse(ll.contains("call void @nabu_throw("),
                "Binnen een try-regio mag de throw geen gewone call zijn:\n" + ll);
    }

    @Test
    void uncaughtThrowUsesCallAndUnreachable() throws Exception {
        String ll = compileAndReadLl(buildThrowingModule(false), CompileOptions.defaults());

        // Zonder try-regio is de throw een gewone call (onbevangen).
        assertTrue(ll.contains("call void @nabu_throw("),
                "Verwacht een gewone call naar @nabu_throw (onbevangen):\n" + ll);
        assertTrue(ll.contains("unreachable"),
                "Verwacht unreachable na de onbevangen throw:\n" + ll);
        assertFalse(ll.contains("invoke void @nabu_throw("),
                "Zonder try-regio mag er geen invoke naar @nabu_throw zijn:\n" + ll);
    }

    /**
     * Fase 3.2 (frontend IR-shape): de frontend emitteert na allocatie van het
     * exception-object een Throw met een niet-null result. Dit toont aan dat de
     * backend het gealloceerde object resolvet (i.p.v. de null-placeholder) en
     * de gooi als invoke + unreachable emitteert.
     */
    @Test
    void throwWithAllocatedObjectResolvesValue() throws Exception {
        IRBuilder b = new IRBuilder("test/Main");
        b.beginFunction("main", IRType.VOID, List.of(), 0L, false);

        IRBasicBlock entry = b.currentBlock();
        IRBasicBlock tryBody = b.beginBlock("try.body");
        IRBasicBlock tryEnd = b.beginBlock("try.end");
        IRBasicBlock handler = b.beginBlock("try.catch.0");

        b.setCurrentBlock(entry);
        b.emitBranch(tryBody);

        // try.body: alloca het exception-object en gooi dat object.
        b.setCurrentBlock(tryBody);
        IRValue exc = b.emitHeapAlloc("exc", EXC_TYPE);
        tryBody.add(new IRInstruction.Throw(exc, EXC_TYPE, b.currentLocation()));

        b.setCurrentBlock(handler);
        handler.add(0, new IRInstruction.TryCatchRegion(
                tryBody.label(), tryEnd.label(), handler.label(), null, b.currentLocation()));
        b.emitReturn(null);

        b.setCurrentBlock(tryEnd);
        b.emitReturn(null);

        b.endFunction();
        String ll = compileAndReadLl(b.build(), CompileOptions.defaults());

        assertTrue(ll.contains("invoke void @nabu_throw("),
                "Verwacht invoke naar @nabu_throw met gealloceerd object:\n" + ll);
        assertTrue(ll.contains("unreachable"),
                "Verwacht unreachable na de throw:\n" + ll);
    }

    private static final String IO_TYPE = "java/io/IOException";

    /**
     * Een try met twee catch-clauses levert één TryCatchRegion per handler op,
     * met dezelfde tryStart/tryEnd. De backend moet daar één range van maken
     * met een geordende dispatch-keten: eerst wordt {@code java/lang/Exception}
     * geprobeerd, bij een mismatch {@code java/io/IOException}, en pas als
     * niets matcht wordt de unwind vervolgd via {@code @nabu_resume}.
     */
    @Test
    void multiCatchBuildsOrderedDispatchChain() throws Exception {
        IRBuilder b = new IRBuilder("test/Main");
        b.beginFunction("main", IRType.VOID, List.of(), 0L, false);

        IRBasicBlock entry = b.currentBlock();
        IRBasicBlock tryBody = b.beginBlock("try.body");
        IRBasicBlock tryEnd = b.beginBlock("try.end");
        IRBasicBlock catch0 = b.beginBlock("try.catch.0");
        IRBasicBlock catch1 = b.beginBlock("try.catch.1");

        b.setCurrentBlock(entry);
        b.emitBranch(tryBody);

        b.setCurrentBlock(tryBody);
        IRValue exc = b.emitHeapAlloc("exc", EXC_TYPE);
        tryBody.add(new IRInstruction.Throw(exc, EXC_TYPE, b.currentLocation()));

        b.setCurrentBlock(catch0);
        catch0.add(0, new IRInstruction.TryCatchRegion(tryBody.label(), tryEnd.label(),
                catch0.label(), "java/lang/Exception", b.currentLocation()));
        b.emitReturn(null);

        b.setCurrentBlock(catch1);
        catch1.add(0, new IRInstruction.TryCatchRegion(tryBody.label(), tryEnd.label(),
                catch1.label(), IO_TYPE, b.currentLocation()));
        b.emitReturn(null);

        b.setCurrentBlock(tryEnd);
        b.emitReturn(null);

        b.endFunction();
        String ll = compileAndReadLl(b.build(), CompileOptions.defaults());

        // Beide clauses moeten in de dispatch-keten zitten: twee type-checks.
        int matches = countOccurrences(ll, "call i32 @nabu_can_catch(");
        assertEquals(2, matches,
                "Verwacht twee nabu_can_catch-checks voor twee catch-clauses:\n" + ll);
        // De clauses in IR-volgorde: Exception eerst, IOException daarna.
        int excAt = ll.indexOf("java/lang/Exception");
        int ioAt = ll.indexOf(IO_TYPE);
        assertTrue(excAt >= 0 && ioAt > excAt,
                "Verwacht de catch-clauses in volgorde (Exception, daarna IOException):\n" + ll);
        // Eén landingspad voor de hele range, met een body-blok per handler.
        assertEquals(1, countOccurrences(ll, "landingpad"),
                "Verwacht één landingpad voor de try-range:\n" + ll);
        assertTrue(ll.contains("handler.try.catch.0.body"),
                "Verwacht een body-blok voor de eerste handler:\n" + ll);
        assertTrue(ll.contains("handler.try.catch.1.body"),
                "Verwacht een body-blok voor de tweede handler:\n" + ll);
        // Niets matcht → de unwind vervolgen, niet opnieuw gooien.
        assertTrue(ll.contains("call void @nabu_resume("),
                "Verwacht @nabu_resume op het pad waar geen clause matcht:\n" + ll);
        // De invoke wijst naar het landingspad-blok van de eerste handler; van
        // daar loopt de keten via %catch.ok.0 naar de eerste body.
        assertTrue(ll.contains("unwind label %try.catch.0"),
                "Verwacht dat de invoke naar het eerste handler-blok leidt:\n" + ll);
        assertTrue(ll.contains("label %handler.try.catch.0.body, label %handler.try.catch.0.check.1"),
                "Verwacht de eerste check met de tweede check als vervolg:\n" + ll);
        assertTrue(ll.contains("label %handler.try.catch.1.body, label %handler.try.catch.0.resume"),
                "Verwacht dat de laatste check doorvalt naar resume:\n" + ll);
    }

    /**
     * Geneste try's: het landingspad van de binnenste range leidt de invoke,
     * en pas wat de binnenste range niet afvangt gaat naar de buitenste
     * handler. De binnenste range wint dus per blok ("last/innermost wins").
     */
    @Test
    void nestedTrysInvokeInnermostHandler() throws Exception {
        IRBuilder b = new IRBuilder("test/Main");
        b.beginFunction("main", IRType.VOID, List.of(), 0L, false);

        IRBasicBlock entry = b.currentBlock();
        IRBasicBlock outerBody = b.beginBlock("outer.body");
        IRBasicBlock innerBody = b.beginBlock("inner.body");
        IRBasicBlock innerEnd = b.beginBlock("inner.end");
        IRBasicBlock outerEnd = b.beginBlock("outer.end");
        IRBasicBlock innerCatch = b.beginBlock("inner.catch.0");
        IRBasicBlock outerCatch = b.beginBlock("outer.catch.0");

        b.setCurrentBlock(entry);
        b.emitBranch(outerBody);

        b.setCurrentBlock(outerBody);
        b.emitBranch(innerBody);

        b.setCurrentBlock(innerBody);
        IRValue exc = b.emitHeapAlloc("exc", EXC_TYPE);
        innerBody.add(new IRInstruction.Throw(exc, EXC_TYPE, b.currentLocation()));

        b.setCurrentBlock(innerEnd);
        b.emitBranch(outerEnd);

        b.setCurrentBlock(innerCatch);
        innerCatch.add(0, new IRInstruction.TryCatchRegion(innerBody.label(),
                innerEnd.label(), innerCatch.label(), IO_TYPE, b.currentLocation()));
        b.emitReturn(null);

        b.setCurrentBlock(outerEnd);
        b.emitReturn(null);

        b.setCurrentBlock(outerCatch);
        outerCatch.add(0, new IRInstruction.TryCatchRegion(outerBody.label(),
                outerEnd.label(), outerCatch.label(), "java/lang/Exception",
                b.currentLocation()));
        b.emitReturn(null);

        b.endFunction();
        String ll = compileAndReadLl(b.build(), CompileOptions.defaults());

        // De throw zit in de binnenste range: de invoke hoort bij het
        // binnenste landingspad, niet bij dat van de buitenste.
        int invokeAt = ll.indexOf("invoke void @nabu_throw(");
        int innerBodyAt = ll.indexOf("handler.inner.catch.0.body");
        int outerBodyAt = ll.indexOf("handler.outer.catch.0.body");
        assertTrue(invokeAt >= 0, "Verwacht een invoke naar @nabu_throw:\n" + ll);
        assertTrue(innerBodyAt > invokeAt && outerBodyAt > innerBodyAt,
                "Verwacht: invoke -> binnenste handler eerst, daarna de buitenste:\n" + ll);
        // Twee ranges, dus twee landingspaden.
        assertEquals(2, countOccurrences(ll, "landingpad"),
                "Verwacht een landingspad per try-range:\n" + ll);
        // De buitenste range heeft één clause, dus die eindigt in resume.
        assertTrue(ll.contains("call void @nabu_resume("),
                "Verwacht @nabu_resume zodat een niet-gevangen exception verder kan:\n" + ll);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}

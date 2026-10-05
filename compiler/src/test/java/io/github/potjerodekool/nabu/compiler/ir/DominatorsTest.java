package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.backend.ir.Dominators;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DominatorsTest {

    // -------------------------------------------------------
    // Lineair blok
    // -------------------------------------------------------

    @Test
    void testLinearChain() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("linear", IRType.VOID, List.of(), 0);

        final var entry = builder.currentBlock();
        final var b1 = builder.beginBlock("b1");
        builder.setCurrentBlock(entry);
        builder.emitBranch(b1);

        final var b2 = builder.beginBlock("b2");
        builder.setCurrentBlock(b1);
        builder.emitBranch(b2);

        final var merge = builder.beginBlock("merge");
        builder.setCurrentBlock(b2);
        builder.emitBranch(merge);

        builder.setCurrentBlock(merge);
        builder.emitReturn(null);
        builder.endFunction();

        final var function = builder.build().functions().getFirst();
        final var dom = Dominators.compute(function);

        // Lineaire keten: entry → b1 → b2 → merge
        assertEquals("entry", dom.getIdom("b1"));
        assertEquals("b1", dom.getIdom("b2"));
        assertEquals("b2", dom.getIdom("merge"));

        assertTrue(dom.dominates("entry", "merge"));
        assertTrue(dom.dominates("b1", "merge"));
        assertTrue(dom.dominates("b2", "merge"));
        assertFalse(dom.dominates("merge", "entry"));
    }

    // -------------------------------------------------------
    // Diamond (if-else)
    // -------------------------------------------------------

    @Test
    void testDiamond() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("diamond", IRType.VOID, List.of(), 0);

        final var entry = builder.currentBlock();
        final var cond = builder.constBool(true);
        final var thenBlk = builder.beginBlock("then");
        final var elseBlk = builder.beginBlock("else");

        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, thenBlk, elseBlk);

        final var merge = builder.beginBlock("merge");
        builder.setCurrentBlock(thenBlk);
        builder.emitBranch(merge);
        builder.setCurrentBlock(elseBlk);
        builder.emitBranch(merge);

        builder.setCurrentBlock(merge);
        builder.emitReturn(null);
        builder.endFunction();

        final var function = builder.build().functions().getFirst();
        final var dom = Dominators.compute(function);

        assertEquals("entry", dom.getIdom("then"));
        assertEquals("entry", dom.getIdom("else"));
        assertEquals("entry", dom.getIdom("merge"));

        assertTrue(dom.dominates("entry", "merge"));
        assertFalse(dom.dominates("then", "merge"));
        assertFalse(dom.dominates("else", "merge"));
    }

    // -------------------------------------------------------
    // Diamond dominance frontiers
    // -------------------------------------------------------

    @Test
    void testDiamondDominanceFrontiers() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("diamond", IRType.VOID, List.of(), 0);

        final var entry = builder.currentBlock();
        final var cond = builder.constBool(true);
        final var thenBlk = builder.beginBlock("then");
        final var elseBlk = builder.beginBlock("else");

        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, thenBlk, elseBlk);

        final var merge = builder.beginBlock("merge");
        builder.setCurrentBlock(thenBlk);
        builder.emitBranch(merge);
        builder.setCurrentBlock(elseBlk);
        builder.emitBranch(merge);

        builder.setCurrentBlock(merge);
        builder.emitReturn(null);
        builder.endFunction();

        final var function = builder.build().functions().getFirst();
        final var dom = Dominators.compute(function);

        // DF(entry) = ∅
        assertTrue(dom.getDominanceFrontier("entry").isEmpty());
        // DF(then) = {merge}
        assertEquals(Set.of("merge"), dom.getDominanceFrontier("then"));
        // DF(else) = {merge}
        assertEquals(Set.of("merge"), dom.getDominanceFrontier("else"));
        // DF(merge) = ∅
        assertTrue(dom.getDominanceFrontier("merge").isEmpty());
    }

    // -------------------------------------------------------
    // While-lus
    // -------------------------------------------------------

    @Test
    void testWhileLoop() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("whileLoop", IRType.VOID, List.of(), 0);

        final var entry = builder.currentBlock();

        final var condBlk = builder.beginBlock("while.cond");
        final var bodyBlk = builder.beginBlock("while.body");
        final var exitBlk = builder.beginBlock("while.exit");

        builder.setCurrentBlock(entry);
        builder.emitBranch(condBlk);

        builder.setCurrentBlock(condBlk);
        final var cond = builder.constBool(true);
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        builder.setCurrentBlock(bodyBlk);
        builder.emitBranch(condBlk);

        builder.setCurrentBlock(exitBlk);
        builder.emitReturn(null);
        builder.endFunction();

        final var function = builder.build().functions().getFirst();
        final var dom = Dominators.compute(function);

        assertEquals("entry", dom.getIdom("while.cond"));
        assertEquals("while.cond", dom.getIdom("while.body"));
        assertEquals("while.cond", dom.getIdom("while.exit"));

        assertTrue(dom.dominates("while.cond", "while.body"));
        assertFalse(dom.dominates("while.body", "while.cond"));
    }

    // -------------------------------------------------------
    // Try/catch: handler-blokken zijn onbereikbaar in het IR-CFG.
    // Daarom bestaan er twee dominator-berekeningen met verschillende
    // contracten: `compute` voor renaming en `computeForPhiPlacement`
    // voor het plaatsen van phi's.
    // -------------------------------------------------------

    /**
     * Bouwt entry -> try.body -> try.end, met een handler try.catch.0 die
     * alleen via de TryCatchRegion in de handler bereikbaar is.
     */
    private static IRFunction buildTryCatchFunction(final String functionName) {
        final var builder = new IRBuilder("test");
        builder.beginFunction(functionName, IRType.VOID, List.of(), 0);

        final var entry = builder.currentBlock();
        final var tryBody = builder.beginBlock("try.body");
        final var tryEnd = builder.beginBlock("try.end");
        final var handler = builder.beginBlock("try.catch.0");

        builder.setCurrentBlock(entry);
        builder.emitBranch(tryBody);

        builder.setCurrentBlock(tryBody);
        builder.emitBranch(tryEnd);

        builder.setCurrentBlock(handler);
        handler.add(0, new io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction.TryCatchRegion(
                tryBody.label(), tryEnd.label(), handler.label(), null,
                io.github.potjerodekool.nabu.debug.SourceLocation.UNKNOWN));
        builder.emitBranch(tryEnd);

        builder.setCurrentBlock(tryEnd);
        builder.emitReturn(null);
        builder.endFunction();

        return builder.build().functions().getFirst();
    }

    /**
     * Contract van {@code Dominators.compute}: alleen de echte CFG-edges. De
     * handler is niet bereikbaar en staat daarom niet in de dominator-boom.
     *
     * <p>Dit is vereist voor SSA-renaming, dat de dominator-boom als
     * benadering van de reele besturingstroom gebruikt. Als de handler wel
     * bereikbaar zou zijn, zou de rename hem bezoeken alsof hij rechtstreeks
     * vanuit entry komt, terwijl hij in werkelijkheid vanaf een willekeurig
     * punt in de try-body binnenkomt. Stores in de handler zouden dan worden
     * verwijderd zonder dat de waarde het merge-blok bereikt.
     */
    @Test
    void testComputeKeepsHandlerOutOfDominatorTree() {
        final var function = buildTryCatchFunction("tryCatchRealCfg");
        final var dom = Dominators.compute(function);

        // Geen idom en geen entry-dominantie: de handler is onbereikbaar.
        assertNull(dom.getIdom("try.catch.0"));
        assertFalse(dom.dominates("entry", "try.catch.0"));
        assertFalse(dom.getPreorder().contains("try.catch.0"));

        // Op de echte CFG geldt wel dat try.body het merge-blok domineert:
        // entry bereikt try.end alleen via try.body. Dit is de conservatieve
        // relatie waarop de rename vertrouwt.
        assertEquals("try.body", dom.getIdom("try.end"));
        assertTrue(dom.dominates("try.body", "try.end"));
    }

    /**
     * Contract van {@code Dominators.computeForPhiPlacement}: de handler is
     * via de virtuele entry-edge bereikbaar, waardoor het merge-blok in het
     * dominance frontier van de try-body belandt en er een phi nodig is.
     *
     * <p>Zonder dit gebeurde het omgekeerde: try.body gold als idom van het
     * merge-blok en de edge van de handler werd genegeerd, dus de waarde uit
     * de handler bereikte het merge-blok nooit.
     */
    @Test
    void testPhiPlacementDominatorsReachHandlerAndPlaceMergePhi() {
        final var function = buildTryCatchFunction("tryCatchPhiPlacement");
        final var dom = Dominators.computeForPhiPlacement(function);

        assertEquals("entry", dom.getIdom("try.catch.0"));
        assertTrue(dom.dominates("entry", "try.catch.0"));

        // Kern van de fix: try.body mag het merge-blok niet domineren, want
        // de handler bereikt try.end zonder door de try-body te gaan.
        assertFalse(dom.dominates("try.body", "try.end"));
        assertFalse(dom.dominates("try.body", "try.catch.0"));

        // Daarom staat try.end in het dominance frontier van de try-body.
        assertTrue(dom.getDominanceFrontier("try.body").contains("try.end"));

        // De augmentatie mag entry's dominantie nooit verzwakken.
        assertTrue(dom.dominates("entry", "try.end"));
    }

    /**
     * De virtuele edge bestaat alleen in de dominatie-berekening.
     * {@code computePredecessors} levert de echte CFG, zodat
     * phi-incoming-waarden alleen echte predecessors bevatten.
     */
    @Test
    void testVirtualHandlerEdgeIsNotInPredecessors() {
        final var function = buildTryCatchFunction("tryCatchPredecessors");
        final var predecessors = Dominators.computePredecessors(function.blocks());

        // De handler heeft geen normale predecessor.
        assertTrue(predecessors.get("try.catch.0").isEmpty());

        // Het merge-blok heeft de try-body en de handler als predecessor,
        // maar geen entry: de phi krijgt dus geen waarde voor een pad dat
        // niet bestaat.
        final var tryEndPreds = predecessors.get("try.end");
        assertEquals(Set.of("try.body", "try.catch.0"), tryEndPreds);
        assertFalse(tryEndPreds.contains("entry"));
    }

    // -------------------------------------------------------
    // Preorder
    // -------------------------------------------------------

    @Test
    void testPreorder() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("preorder", IRType.VOID, List.of(), 0);

        final var entry = builder.currentBlock();
        final var cond = builder.constBool(true);
        final var thenBlk = builder.beginBlock("then");
        final var elseBlk = builder.beginBlock("else");

        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, thenBlk, elseBlk);

        final var merge = builder.beginBlock("merge");
        builder.setCurrentBlock(thenBlk);
        builder.emitBranch(merge);
        builder.setCurrentBlock(elseBlk);
        builder.emitBranch(merge);

        builder.setCurrentBlock(merge);
        builder.emitReturn(null);
        builder.endFunction();

        final var function = builder.build().functions().getFirst();
        final var dom = Dominators.compute(function);
        final var preorder = dom.getPreorder();

        // Entry moet eerste zijn
        assertEquals("entry", preorder.getFirst());
        // Alle blokken aanwezig
        assertEquals(4, preorder.size());
        assertTrue(preorder.containsAll(List.of("entry", "then", "else", "merge")));
    }
}

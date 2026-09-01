package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.backend.ir.Dominators;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
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

package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRBuilder;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.IRModule;
import io.github.potjerodekool.nabu.compiler.ir.CallKind;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhiEliminationTest {

    // -------------------------------------------------------
    // Basis: diamond-CFG met één phi
    // -------------------------------------------------------

    /**
     * Construeert een diamond-CFG:
     *
     *   entry:
     *     cond = (x > 0)
     *     br cond, then, else
     *
     *   then:
     *     %1 = 42
     *     br merge
     *
     *   else:
     *     %2 = 99
     *     br merge
     *
     *   merge:
     *     %result = phi(%1 from then, %2 from else)
     *     return %result
     */
    @Test
    void testSinglePhiInDiamond() {
        final var builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);

        final var param = new IRValue.Temp("%x", IRType.I32);
        builder.beginFunction("foo", IRType.I32, List.of(param), 0);

        // entry block
        final var entry = builder.currentBlock();
        final var x = builder.lookup("x");
        final var zero = builder.constInt(0);
        final var cond = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.GT, x, zero);

        final var thenBlk = builder.beginBlock("then");
        final var elseBlk = builder.beginBlock("else");
        final var mergeBlk = builder.beginBlock("merge");

        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, thenBlk, elseBlk);

        // then block: %1 = 42
        builder.setCurrentBlock(thenBlk);
        final var val42 = builder.constInt(42);
        builder.emitBranch(mergeBlk);

        // else block: %2 = 99
        builder.setCurrentBlock(elseBlk);
        final var val99 = builder.constInt(99);
        builder.emitBranch(mergeBlk);

        // phi in merge
        builder.setCurrentBlock(mergeBlk);
        final var phiResult = builder.emitPhi(IRType.I32, List.of(
                new IRInstruction.Phi.Incoming(val42, thenBlk),
                new IRInstruction.Phi.Incoming(val99, elseBlk)
        ));
        builder.emitReturn(phiResult);

        builder.endFunction();

        // Controleer dat de phi aanwezig is
        final var mergeInstructions = mergeBlk.instructions();
        assertTrue(mergeInstructions.get(0) instanceof IRInstruction.Phi,
                "Eerste instructie van merge-blok moet een Phi zijn");

        // Voer phi-eliminatie uit
        PhiElimination.run(builder.build());

        // Controleer dat de phi verwijderd is
        final var mergeAfter = mergeBlk.instructions();
        for (final var instr : mergeAfter) {
            assertFalse(instr instanceof IRInstruction.Phi,
                    "Geen Phi-instructies meer verwacht na eliminatie");
        }

        // Controleer dat de then-block een store heeft gekregen
        final var thenInstrs = thenBlk.instructions();
        boolean hasStoreInThen = thenInstrs.stream()
                .anyMatch(i -> i instanceof IRInstruction.Store);
        assertTrue(hasStoreInThen, "Then-blok moet een Store hebben na phi-eliminatie");

        // Controleer dat de else-block een store heeft gekregen
        final var elseInstrs = elseBlk.instructions();
        boolean hasStoreInElse = elseInstrs.stream()
                .anyMatch(i -> i instanceof IRInstruction.Store);
        assertTrue(hasStoreInElse, "Else-blok moet een Store hebben na phi-eliminatie");

        // Controleer dat het merge-blok een load heeft gekregen
        boolean hasLoadInMerge = mergeAfter.stream()
                .anyMatch(i -> i instanceof IRInstruction.Load);
        assertTrue(hasLoadInMerge, "Merge-blok moet een Load hebben na phi-eliminatie");
    }

    // -------------------------------------------------------
    // Geen phis → geen verandering
    // -------------------------------------------------------

    @Test
    void testNoPhisIsNoop() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("simple", IRType.I32, List.of(), 0);

        builder.emitReturn(builder.constInt(7));
        builder.endFunction();

        final var module = builder.build();
        final var function = module.functions().getFirst();
        final var blockBefore = function.entryBlock().instructions().size();

        PhiElimination.run(module);

        final var blockAfter = function.entryBlock().instructions().size();
        assertEquals(blockBefore, blockAfter, "Geen instructies mogen veranderd zijn");
    }

    // -------------------------------------------------------
    // Meerdere phis in hetzelfde blok
    // -------------------------------------------------------

    @Test
    void testMultiplePhisInSameBlock() {
        final var builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);

        builder.beginFunction("multi", IRType.I32, List.of(), 0);

        // entry: cond branch
        final var entry = builder.currentBlock();
        final var cond = builder.constBool(true);
        final var thenBlk = builder.beginBlock("then");
        final var elseBlk = builder.beginBlock("else");

        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, thenBlk, elseBlk);

        // then
        builder.setCurrentBlock(thenBlk);
        final var a1 = builder.constInt(10);
        final var b1 = builder.constInt(20);

        // else
        builder.setCurrentBlock(elseBlk);
        final var a2 = builder.constInt(30);
        final var b2 = builder.constInt(40);

        // merge
        final var mergeBlk = builder.beginBlock("merge");
        builder.setCurrentBlock(thenBlk);
        builder.emitBranch(mergeBlk);
        builder.setCurrentBlock(elseBlk);
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        final var phiA = builder.emitPhi(IRType.I32, List.of(
                new IRInstruction.Phi.Incoming(a1, thenBlk),
                new IRInstruction.Phi.Incoming(a2, elseBlk)
        ));
        final var phiB = builder.emitPhi(IRType.I32, List.of(
                new IRInstruction.Phi.Incoming(b1, thenBlk),
                new IRInstruction.Phi.Incoming(b2, elseBlk)
        ));

        // Gebruik beide phi-resultaten
        final var sum = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, phiA, phiB);
        builder.emitReturn(sum);
        builder.endFunction();

        final var module = builder.build();
        PhiElimination.run(module);

        // Geen phis meer
        for (final var block : mergeBlk.instructions()) {
            assertFalse(block instanceof IRInstruction.Phi);
        }

        // Merge moet 2 loads hebben (één per phi)
        final long loadCount = mergeBlk.instructions().stream()
                .filter(i -> i instanceof IRInstruction.Load)
                .count();
        assertEquals(2, loadCount, "Merge-blok moet 2 loads hebben");

        // Then moet 2 stores hebben
        final long thenStores = thenBlk.instructions().stream()
                .filter(i -> i instanceof IRInstruction.Store)
                .count();
        assertEquals(2, thenStores, "Then-blok moet 2 stores hebben");
    }

    // -------------------------------------------------------
    // PhiElimination op module-niveau
    // -------------------------------------------------------

    @Test
    void testModuleLevelElimination() {
        final var builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);

        // Functie 1: geen phi
        builder.beginFunction("noPhi", IRType.VOID, List.of(), 0);
        builder.emitReturn(null);
        builder.endFunction();

        // Functie 2: diamond met phi
        builder.beginFunction("withPhi", IRType.I32, List.of(), 0);
        final var entry = builder.currentBlock();
        final var cond = builder.constBool(true);
        final var t = builder.beginBlock("t");
        final var e = builder.beginBlock("e");
        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, t, e);
        builder.setCurrentBlock(t);
        final var v1 = builder.constInt(1);
        final var merge = builder.beginBlock("m");
        builder.setCurrentBlock(t);
        builder.emitBranch(merge);
        builder.setCurrentBlock(e);
        final var v2 = builder.constInt(2);
        builder.emitBranch(merge);
        builder.setCurrentBlock(merge);
        final var phi = builder.emitPhi(IRType.I32, List.of(
                new IRInstruction.Phi.Incoming(v1, t),
                new IRInstruction.Phi.Incoming(v2, e)
        ));
        builder.emitReturn(phi);
        builder.endFunction();

        final var module = builder.build();
        PhiElimination.run(module);

        // Functie 1: onaangetast
        assertEquals(1, module.functions().get(0).entryBlock().instructions().size());

        // Functie 2: merge-blok heeft geen phis meer
        final var mergeBlock = module.functions().get(1).blocks().stream()
                .filter(b -> b.label().equals("m"))
                .findFirst().orElseThrow();
        assertTrue(mergeBlock.instructions().stream().noneMatch(IRInstruction.Phi.class::isInstance));
    }

    // -------------------------------------------------------
    // PhiElimination met nested diamond (2 opeenvolgende phi's)
    // -------------------------------------------------------

    @Test
    void testNestedPhiChain() {
        final var builder = new IRBuilder("test");
        builder.setLocation("test.lang", 1, 1);

        builder.beginFunction("chain", IRType.I32, List.of(), 0);

        final var entry = builder.currentBlock();
        final var c1 = builder.constBool(true);
        final var b1 = builder.beginBlock("b1");
        final var b2 = builder.beginBlock("b2");
        builder.setCurrentBlock(entry);
        builder.emitCondBranch(c1, b1, b2);

        // b1
        builder.setCurrentBlock(b1);
        final var v1 = builder.constInt(10);
        final var merge1 = builder.beginBlock("merge1");
        builder.setCurrentBlock(b1);
        builder.emitBranch(merge1);

        // b2
        builder.setCurrentBlock(b2);
        final var v2 = builder.constInt(20);
        builder.emitBranch(merge1);

        // merge1: eerste phi
        builder.setCurrentBlock(merge1);
        final var phi1 = builder.emitPhi(IRType.I32, List.of(
                new IRInstruction.Phi.Incoming(v1, b1),
                new IRInstruction.Phi.Incoming(v2, b2)
        ));

        // Tweede diamond na merge1
        final var c2 = builder.constBool(false);
        final var b3 = builder.beginBlock("b3");
        final var b4 = builder.beginBlock("b4");
        builder.setCurrentBlock(merge1);
        builder.emitCondBranch(c2, b3, b4);

        // b3
        builder.setCurrentBlock(b3);
        final var v3 = builder.constInt(30);
        final var merge2 = builder.beginBlock("merge2");
        builder.setCurrentBlock(b3);
        builder.emitBranch(merge2);

        // b4
        builder.setCurrentBlock(b4);
        final var v4 = builder.constInt(40);
        builder.emitBranch(merge2);

        // merge2: tweede phi
        builder.setCurrentBlock(merge2);
        final var phi2 = builder.emitPhi(IRType.I32, List.of(
                new IRInstruction.Phi.Incoming(v3, b3),
                new IRInstruction.Phi.Incoming(v4, b4)
        ));

        // Gebruik phi1 + phi2
        final var sum = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, phi1, phi2);
        builder.emitReturn(sum);
        builder.endFunction();

        final var module = builder.build();
        PhiElimination.run(module);

        // Geen enkele phi meer in de hele functie
        for (final var block : module.functions().getFirst().blocks()) {
            for (final var instr : block.instructions()) {
                assertFalse(instr instanceof IRInstruction.Phi,
                        "Geen phi verwacht in blok " + block.label());
            }
        }
    }
}

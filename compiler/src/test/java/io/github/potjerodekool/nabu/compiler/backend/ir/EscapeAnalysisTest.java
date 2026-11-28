package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.optimize.DeadCodeElimination;
import io.github.potjerodekool.nabu.backend.ir.optimize.EscapeAnalysis;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Escape analysis must be sound: an allocation only counts as non-escaping when nothing can keep a reference.
 * A wrong answer turns into a stack object that outlives its frame (crashes in the native image).
 */
class EscapeAnalysisTest {

    private static final SourceLocation LOC = SourceLocation.UNKNOWN;
    private static final IRType PTR = new IRType.Ptr(IRType.I8);

    private static IRValue temp(final String name) {
        return new IRValue.Temp(name, PTR);
    }

    private static IRFunction function(final String name, final List<IRValue> params,
                                       final IRInstruction... instructions) {
        final var f = new IRFunction(name, IRType.VOID, params, LOC, 0);
        final var block = new IRBasicBlock("entry");
        for (final var in : instructions) block.add(in);
        f.addBlock(block);
        return f;
    }

    private static IRInstruction.Call call(final CallKind kind, final String target,
                                           final IRValue... args) {
        return new IRInstruction.Call(kind, IRType.VOID, List.of(), null, target, List.of(args), LOC);
    }

    private static EscapeAnalysis.Result analyze(final IRFunction f, final IRFunction... callees) {
        final var map = new java.util.HashMap<String, IRFunction>();
        for (final var c : callees) map.put(c.name, c);
        return EscapeAnalysis.analyze(f, map);
    }

    private static IRInstruction.HeapAlloc alloc(final String name) {
        return new IRInstruction.HeapAlloc(temp(name), IRType.I8, LOC);
    }

    @Test
    void unusedAllocationDoesNotEscape() {
        final var f = function("f", List.of(), alloc("o"), new IRInstruction.Return(null, LOC));
        assertTrue(analyze(f).nonEscaping.contains("o"));
    }

    @Test
    void returnedAllocationEscapes() {
        final var f = function("f", List.of(), alloc("o"), new IRInstruction.Return(temp("o"), LOC));
        assertFalse(analyze(f).nonEscaping.contains("o"));
    }

    @Test
    void aliasThroughCastIsFollowed() {
        final var f = function("f", List.of(), alloc("o"),
                new IRInstruction.Cast(temp("c"), temp("o"), PTR, LOC),
                new IRInstruction.Return(temp("c"), LOC));
        assertFalse(analyze(f).nonEscaping.contains("o"));
    }

    @Test
    void aliasThroughMoveStoredIntoAFieldEscapes() {
        final var f = function("f", List.of(temp("holder")), alloc("o"),
                new IRInstruction.Move(temp("m"), temp("o"), LOC),
                new IRInstruction.Store(temp("holder"), temp("m"), LOC),
                new IRInstruction.Return(null, LOC));
        assertFalse(analyze(f).nonEscaping.contains("o"));
    }

    @Test
    void allocationOutsideTheEntryBlockQualifiesWhenNotInALoop() {
        final var f = new IRFunction("f", IRType.VOID, List.of(), LOC, 0);
        final var entry = new IRBasicBlock("entry");
        entry.add(new IRInstruction.Branch("next", LOC));
        f.addBlock(entry);
        final var next = new IRBasicBlock("next");
        next.add(alloc("o"));
        next.add(new IRInstruction.Return(null, LOC));
        f.addBlock(next);
        assertTrue(analyze(f).nonEscaping.contains("o"));
    }

    @Test
    void allocationInALoopNeverQualifies() {
        final var f = new IRFunction("f", IRType.VOID, List.of(), LOC, 0);
        final var entry = new IRBasicBlock("entry");
        entry.add(new IRInstruction.Branch("loop", LOC));
        f.addBlock(entry);
        final var loop = new IRBasicBlock("loop");
        loop.add(alloc("o"));
        loop.add(new IRInstruction.Branch("loop", LOC));
        f.addBlock(loop);
        assertFalse(analyze(f).nonEscaping.contains("o"));
    }

    /** The bytecode lowering spills a parameter into a slot (an Alloca) and loads it from there. */
    private static IRFunction spillingCallee(final String name, final boolean storesItInAField) {
        final var instrs = new java.util.ArrayList<IRInstruction>();
        instrs.add(new IRInstruction.Alloca(temp("p.ptr"), PTR, LOC));
        instrs.add(new IRInstruction.Store(temp("p.ptr"), temp("p"), LOC));
        instrs.add(new IRInstruction.Load(temp("l"), PTR, temp("p.ptr"), LOC));
        if (storesItInAField) {
            instrs.add(new IRInstruction.Store(temp("holder"), temp("l"), LOC));
        }
        instrs.add(new IRInstruction.Return(null, LOC));
        return function(name, List.of(temp("p"), temp("holder")), instrs.toArray(new IRInstruction[0]));
    }

    @Test
    void parameterSpilledIntoASlotAndOnlyReadIsNoEscape() {
        final var callee = spillingCallee("reader", false);
        final var f = function("f", List.of(temp("h")), alloc("o"),
                call(CallKind.STATIC, "reader", temp("o"), temp("h")), new IRInstruction.Return(null, LOC));
        assertTrue(analyze(f, callee).nonEscaping.contains("o"));
    }

    @Test
    void parameterSpilledIntoASlotAndLoadedBackIntoAFieldEscapes() {
        final var callee = spillingCallee("keeper", true);
        final var f = function("f", List.of(temp("h")), alloc("o"),
                call(CallKind.STATIC, "keeper", temp("o"), temp("h")), new IRInstruction.Return(null, LOC));
        assertFalse(analyze(f, callee).nonEscaping.contains("o"));
    }

    @Test
    void slotWhoseAddressIsPassedOnIsNotPlain() {
        // the slot itself leaves the function: what is stored in it can be reached from elsewhere
        final var f = function("f", List.of(temp("h")),
                new IRInstruction.Alloca(temp("s"), PTR, LOC), alloc("o"),
                new IRInstruction.Store(temp("s"), temp("o"), LOC),
                new IRInstruction.Store(temp("h"), temp("s"), LOC),
                new IRInstruction.Return(null, LOC));
        assertFalse(analyze(f).nonEscaping.contains("o"));
    }

    @Test
    void calleeThatOnlyReadsItsParameterIsFine() {
        final var callee = function("reader", List.of(temp("p")), new IRInstruction.Return(null, LOC));
        final var f = function("f", List.of(), alloc("o"),
                call(CallKind.STATIC, "reader", temp("o")), new IRInstruction.Return(null, LOC));
        assertTrue(analyze(f, callee).nonEscaping.contains("o"));
    }

    @Test
    void calleeThatPassesItsParameterOnEscapes() {
        // keeper(p) -> sink(p), and sink is unknown: p may be kept
        final var keeper = function("keeper", List.of(temp("p")),
                call(CallKind.STATIC, "unknownSink", temp("p")), new IRInstruction.Return(null, LOC));
        final var f = function("f", List.of(), alloc("o"),
                call(CallKind.STATIC, "keeper", temp("o")), new IRInstruction.Return(null, LOC));
        assertFalse(analyze(f, keeper).nonEscaping.contains("o"));
    }

    @Test
    void calleeThatStoresItsParameterAfterAliasingEscapes() {
        final var keeper = function("keeper", List.of(temp("p"), temp("holder")),
                new IRInstruction.Cast(temp("q"), temp("p"), PTR, LOC),
                new IRInstruction.Store(temp("holder"), temp("q"), LOC),
                new IRInstruction.Return(null, LOC));
        final var f = function("f", List.of(temp("h")), alloc("o"),
                call(CallKind.STATIC, "keeper", temp("o"), temp("h")), new IRInstruction.Return(null, LOC));
        assertFalse(analyze(f, keeper).nonEscaping.contains("o"));
    }

    @Test
    void virtualCallMayReachAnOverrideThatKeepsTheReceiver() {
        final var base = function("Base_run", List.of(temp("self")), new IRInstruction.Return(null, LOC));
        final var f = function("f", List.of(), alloc("o"),
                call(CallKind.VIRTUAL, "Base_run", temp("o")), new IRInstruction.Return(null, LOC));
        assertFalse(analyze(f, base).nonEscaping.contains("o"));
    }

    /** The source front end leaves the receiver of a constructor call out of the arguments. */
    @Test
    void implicitConstructorReceiverIsAnalysed() {
        final var keepingInit = function("Foo_init", List.of(temp("this"), temp("registry")),
                new IRInstruction.Store(temp("registry"), temp("this"), LOC),
                new IRInstruction.Return(null, LOC));
        final var plainInit = function("Bar_init", List.of(temp("this")), new IRInstruction.Return(null, LOC));

        final var keeps = function("f", List.of(temp("reg")), alloc("o"),
                new IRInstruction.Call(CallKind.SPECIAL, IRType.VOID, List.of(PTR), null, "Foo_init",
                        List.of(temp("reg")), LOC),
                new IRInstruction.Return(null, LOC));
        assertFalse(analyze(keeps, keepingInit).nonEscaping.contains("o"));

        final var plain = function("g", List.of(), alloc("o"),
                new IRInstruction.Call(CallKind.SPECIAL, IRType.VOID, List.of(), null, "Bar_init",
                        List.of(), LOC),
                new IRInstruction.Return(null, LOC));
        assertTrue(analyze(plain, plainInit).nonEscaping.contains("o"));
    }

    /**
     * The receiver of a constructor call is not an operand, so the result of a StackAlloc looks unused. Removing
     * it left 'undef' as 'this' in the native image.
     */
    @Test
    void deadCodeEliminationKeepsAStackAllocWhoseConstructorReceiverIsImplicit() {
        final var f = function("f", List.of(),
                new IRInstruction.StackAlloc(temp("o"), IRType.I8, LOC),
                new IRInstruction.Call(CallKind.SPECIAL, IRType.VOID, List.of(), null, "Bar_init", List.of(), LOC),
                new IRInstruction.Return(null, LOC));
        new DeadCodeElimination().run(f);
        assertEquals(1, f.blocks().getFirst().instructions().stream()
                .filter(i -> i instanceof IRInstruction.StackAlloc).count());
    }
}

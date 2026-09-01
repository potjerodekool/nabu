package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.optimize.CopyPropagation;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CopyPropagationTest {

    private long countMoves(IRModule module) {
        return module.functions().getFirst().blocks().stream()
                .flatMap(b -> b.instructions().stream())
                .filter(i -> i instanceof IRInstruction.Move)
                .count();
    }

    private IRModule buildModule(Consumer<IRBuilder> builder) {
        final var b = new IRBuilder("test");
        b.setLocation("test.lang", 1, 1);
        builder.accept(b);
        b.endFunction();
        return b.build();
    }

    @Test
    void testSimpleMoveRemoval() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var constVal = b.constInt(42);
            final var move = b.emitMove(IRType.I32, constVal);
            b.emitReturn(move);
        });

        final var function = module.functions().getFirst();
        assertEquals(1, countMoves(module), "1 Move vóór propagatie");

        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Geen Moves na propagatie");
    }

    @Test
    void testMoveChainPropagation() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(10);
            final var move1 = b.emitMove(IRType.I32, a);
            final var move2 = b.emitMove(IRType.I32, move1);
            b.emitReturn(move2);
        });

        final var function = module.functions().getFirst();
        assertEquals(2, countMoves(module), "2 Moves vóór propagatie");

        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Geen Moves na propagatie (keten opgelost)");
    }

    @Test
    void testMoveUsedInBinaryOp() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(5);
            final var move = b.emitMove(IRType.I32, a);
            final var r = b.constInt(3);
            final var sum = b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, move, r);
            b.emitReturn(sum);
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Move verwijderd");

        // De BinaryOp zou nu %a (constInt 5) moeten gebruiken i.p.v. het move-resultaat
        final var binaryOps = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.BinaryOp)
                .map(i -> (IRInstruction.BinaryOp) i)
                .toList();

        assertEquals(1, binaryOps.size());
        assertTrue(binaryOps.getFirst().left() instanceof IRValue.ConstInt,
                "Linker operand zou nu de constante waarde moeten zijn");
    }

    @Test
    void testMoveUsedInReturn() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(99);
            final var move = b.emitMove(IRType.I32, a);
            b.emitReturn(move);
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        final var returns = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.Return)
                .map(i -> (IRInstruction.Return) i)
                .toList();

        assertEquals(1, returns.size());
        assertTrue(returns.getFirst().value() instanceof IRValue.ConstInt,
                "Return zou nu de constante waarde moeten bevatten");
    }

    @Test
    void testNoMovesNothingToDo() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(10);
            final var r = b.constInt(20);
            final var sum = b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, r);
            b.emitReturn(sum);
        });

        final var function = module.functions().getFirst();
        assertFalse(new CopyPropagation().run(function),
                "Geen wijzigingen als er geen moves zijn");
    }

    @Test
    void testMoveUsedInArrayLoad() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var arr = b.constInt(0);
            final var moveArr = b.emitMove(new IRType.Ptr(IRType.I32), arr);
            final var idx = b.constInt(0);
            final var load = b.emitArrayLoad(moveArr, idx, IRType.I32);
            b.emitReturn(load);
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        final var arrayLoads = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.ArrayLoad)
                .map(i -> (IRInstruction.ArrayLoad) i)
                .toList();

        assertEquals(1, arrayLoads.size());
        assertTrue(arrayLoads.getFirst().array() instanceof IRValue.ConstInt,
                "ArrayLoad zou nu de originele array-waarde moeten bevatten na propagatie");
    }

    @Test
    void testMoveUsedInArrayStore() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.VOID, List.of(), 0);
            final var arr = b.constInt(0);
            final var moveArr = b.emitMove(new IRType.Ptr(IRType.I32), arr);
            final var idx = b.constInt(0);
            final var val = b.constInt(42);
            final var moveVal = b.emitMove(IRType.I32, val);
            b.emitArrayStore(moveArr, idx, moveVal, IRType.I32);
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Moves verwijderd na propagatie");

        final var arrayStores = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.ArrayStore)
                .map(i -> (IRInstruction.ArrayStore) i)
                .toList();

        assertEquals(1, arrayStores.size());
        assertTrue(arrayStores.getFirst().array() instanceof IRValue.ConstInt,
                "ArrayStore zou nu de originele array-waarde moeten bevatten");
        assertTrue(arrayStores.getFirst().value() instanceof IRValue.ConstInt,
                "ArrayStore zou nu de originele value-waarde moeten bevatten");
    }

    @Test
    void testMoveUsedInArrayLength() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var arr = b.constInt(0);
            final var moveArr = b.emitMove(new IRType.Ptr(IRType.I32), arr);
            final var len = b.emitArrayLength(moveArr);
            b.emitReturn(len);
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Move verwijderd");

        final var arrayLengths = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.ArrayLength)
                .map(i -> (IRInstruction.ArrayLength) i)
                .toList();

        assertEquals(1, arrayLengths.size());
        assertTrue(arrayLengths.getFirst().array() instanceof IRValue.ConstInt,
                "ArrayLength zou nu de originele array-waarde moeten bevatten");
    }

    @Test
    void testMoveUsedInInstanceOf() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.BOOL, List.of(), 0);
            final var obj = b.constInt(0);
            final var moveObj = b.emitMove(new IRType.Ptr(IRType.VOID), obj);
            final var io = b.emitInstanceOf(moveObj, IRType.I32);
            b.emitReturn(io);
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Move verwijderd");

        final var instanceOfs = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.InstanceOf)
                .map(i -> (IRInstruction.InstanceOf) i)
                .toList();

        assertEquals(1, instanceOfs.size());
        assertTrue(instanceOfs.getFirst().source() instanceof IRValue.ConstInt,
                "InstanceOf zou nu de originele bron-waarde moeten bevatten");
    }

    @Test
    void testMoveUsedInCondBranch() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.VOID, List.of(), 0);
            final var cond = b.constBool(true);
            final var moveCond = b.emitMove(IRType.BOOL, cond);
            final var trueBlock = b.beginBlock("true");
            b.emitReturn(null);
            b.setCurrentBlock(b.currentFunction().blocks().getFirst());
            b.emitCondBranch(moveCond, trueBlock.label(), "false");
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Move verwijderd");

        final var condBranches = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.CondBranch)
                .map(i -> (IRInstruction.CondBranch) i)
                .toList();

        assertEquals(1, condBranches.size());
        assertTrue(condBranches.getFirst().condition() instanceof IRValue.ConstBool,
                "CondBranch zou nu de originele voorwaarde moeten bevatten");
    }

    @Test
    void testMoveUsedInCast() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I64, List.of(), 0);
            final var val = b.constInt(42);
            final var moveVal = b.emitMove(IRType.I32, val);
            final var cast = b.emitCast(moveVal, IRType.I64);
            b.emitReturn(cast);
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Move verwijderd");

        final var casts = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.Cast)
                .map(i -> (IRInstruction.Cast) i)
                .toList();

        assertEquals(1, casts.size());
        assertTrue(casts.getFirst().source() instanceof IRValue.ConstInt,
                "Cast zou nu de originele bron-waarde moeten bevatten");
    }

    @Test
    void testMoveUsedInMonitor() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.VOID, List.of(), 0);
            final var obj = b.constInt(0);
            final var moveObj = b.emitMove(new IRType.Ptr(IRType.VOID), obj);
            b.emitMonitorEnter(moveObj);
            b.emitMonitorExit(moveObj);
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Moves verwijderd");

        final var monEnters = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.MonitorEnter)
                .map(i -> (IRInstruction.MonitorEnter) i)
                .toList();

        assertEquals(1, monEnters.size());
        assertTrue(monEnters.getFirst().object() instanceof IRValue.ConstInt,
                "MonitorEnter zou nu de originele object-waarde moeten bevatten");
    }

    @Test
    void testMoveUsedInThrow() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.VOID, List.of(), 0);
            final var ex = b.constInt(0);
            final var moveEx = b.emitMove(new IRType.Ptr(IRType.VOID), ex);
            b.emitThrow(moveEx.type());
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Move verwijderd");
    }

    @Test
    void testMoveUsedInStore() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.VOID, List.of(), 0);
            final var ptr = b.constInt(0);
            final var movePtr = b.emitMove(new IRType.Ptr(IRType.I32), ptr);
            final var val = b.constInt(99);
            final var moveVal = b.emitMove(IRType.I32, val);
            b.emitStore(movePtr, moveVal);
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Moves verwijderd");

        final var stores = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.Store)
                .map(i -> (IRInstruction.Store) i)
                .toList();

        assertEquals(1, stores.size());
        assertTrue(stores.getFirst().ptr() instanceof IRValue.ConstInt,
                "Store zou nu de originele pointer moeten bevatten");
        assertTrue(stores.getFirst().value() instanceof IRValue.ConstInt,
                "Store zou nu de originele waarde moeten bevatten");
    }

    @Test
    void testMoveUsedInLoad() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var ptr = b.constInt(0);
            final var movePtr = b.emitMove(new IRType.Ptr(IRType.I32), ptr);
            final var load = b.emitLoad(IRType.I32, movePtr);
            b.emitReturn(load);
        });

        final var function = module.functions().getFirst();
        new CopyPropagation().run(function);

        assertEquals(0, countMoves(module), "Move verwijderd");

        final var loads = function.blocks().stream()
                .flatMap(b2 -> b2.instructions().stream())
                .filter(i -> i instanceof IRInstruction.Load)
                .map(i -> (IRInstruction.Load) i)
                .toList();

        assertEquals(1, loads.size());
        assertTrue(loads.getFirst().ptr() instanceof IRValue.ConstInt,
                "Load zou nu de originele pointer moeten bevatten");
    }
}

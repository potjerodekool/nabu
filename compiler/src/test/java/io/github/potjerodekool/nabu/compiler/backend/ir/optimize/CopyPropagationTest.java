package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.compiler.ir.IRBuilder;
import io.github.potjerodekool.nabu.compiler.ir.IRModule;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
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
}

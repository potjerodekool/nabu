package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.optimize.ConstantFolder;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConstantFolderTest {

    private long countType(IRFunction f, Class<? extends IRInstruction> type) {
        return f.blocks().stream()
                .flatMap(b -> b.instructions().stream())
                .filter(type::isInstance)
                .count();
    }

    private IRModule buildModule(java.util.function.Consumer<IRBuilder> builder) {
        final var b = new IRBuilder("test");
        b.setLocation("test.lang", 1, 1);
        builder.accept(b);
        b.endFunction();
        return b.build();
    }

    @Test
    void testFoldIntegerAddition() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var l = b.constInt(3);
            final var r = b.constInt(4);
            final var result = b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, l, r);
            b.emitReturn(result);
        });

        final var function = module.functions().getFirst();
        final var folder = new ConstantFolder();
        assertTrue(folder.run(function), "Fold zou wijzigingen moeten aanbrengen");

        final var moves = function.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(i -> i instanceof IRInstruction.Move)
                .map(i -> (IRInstruction.Move) i)
                .toList();

        assertEquals(1, moves.size(), "Één Move verwacht (gefolds resultaat)");

        final var value = moves.getFirst().value();
        assertTrue(value instanceof IRValue.ConstInt, "Gefolds waarde moet een ConstInt zijn");
        assertEquals(7, ((IRValue.ConstInt) value).value(), "3 + 4 = 7");
    }

    @Test
    void testFoldIntegerComparison() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.BOOL, List.of(), 0);
            final var l = b.constInt(10);
            final var r = b.constInt(5);
            final var result = b.emitBinaryOp(IRInstruction.BinaryOp.Op.GT, l, r);
            b.emitReturn(result);
        });

        final var function = module.functions().getFirst();
        new ConstantFolder().run(function);

        final var moves = function.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(i -> i instanceof IRInstruction.Move)
                .map(i -> (IRInstruction.Move) i)
                .toList();

        assertEquals(1, moves.size());
        final var value = moves.getFirst().value();
        assertTrue(value instanceof IRValue.ConstBool);
        assertTrue(((IRValue.ConstBool) value).value(), "10 > 5 = true");
    }

    @Test
    void testFoldBooleanAnd() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.BOOL, List.of(), 0);
            final var l = b.constBool(true);
            final var r = b.constBool(false);
            final var result = b.emitBinaryOp(IRInstruction.BinaryOp.Op.AND, l, r);
            b.emitReturn(result);
        });

        final var function = module.functions().getFirst();
        new ConstantFolder().run(function);

        final var moves = function.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(i -> i instanceof IRInstruction.Move)
                .map(i -> (IRInstruction.Move) i)
                .toList();

        assertEquals(1, moves.size());
        assertFalse(((IRValue.ConstBool) moves.getFirst().value()).value(),
                "true && false = false");
    }

    @Test
    void testNoFoldingForVariables() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var x = new IRValue.Temp("%x", IRType.I32);
            final var r = b.constInt(4);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, x, r);
            b.emitReturn(x);
        });

        final var function = module.functions().getFirst();
        final var folder = new ConstantFolder();
        assertFalse(folder.run(function), "Geen folding voor variabelen");

        final var binaryOps = countType(function, IRInstruction.BinaryOp.class);
        assertEquals(1, binaryOps, "BinaryOp moet bewaard blijven");
    }

    @Test
    void testFoldMultipleExpressions() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(10);
            final var bVal = b.constInt(20);
            final var sum = b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, bVal);
            final var c = b.constInt(5);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.SUB, sum, c);
            b.emitReturn(sum);
        });

        final var function = module.functions().getFirst();
        new ConstantFolder().run(function);

        // Eerste BinaryOp wordt gefold (beide operanden zijn constants).
        // Tweede BinaryOp kan niet folden want %sum is een Temp (niet ConstInt).
        // Pas de pipeline (met CopyPropagation) kan de tweede ook folden.
        assertEquals(1, countType(function, IRInstruction.BinaryOp.class),
                "Eerste BinaryOp gefold, tweede nog niet (Temp-referentie)");
    }
}

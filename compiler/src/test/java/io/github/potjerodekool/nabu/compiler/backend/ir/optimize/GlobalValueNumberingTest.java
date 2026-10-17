package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.optimize.GlobalValueNumbering;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class GlobalValueNumberingTest {

    private long countType(IRModule module, Class<? extends IRInstruction> type) {
        return module.functions().getFirst().blocks().stream()
                .flatMap(b -> b.instructions().stream())
                .filter(type::isInstance)
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
    void testDuplicateExpressions() {
        // %1 = 3 + 4
        // %2 = 3 + 4    → zou %2 = %1 moeten worden
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(3);
            final var c = b.constInt(4);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, c);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, c);
            b.emitReturn(b.constInt(0));
        });

        final var function = module.functions().getFirst();
        final var before = countType(module, IRInstruction.BinaryOp.class);

        new GlobalValueNumbering().run(function);

        final var after = countType(module, IRInstruction.BinaryOp.class);
        assertTrue(after < before,
                "Één BinaryOp zou vervangen moeten worden door een Move");
    }

    @Test
    void testDifferentExpressions() {
        // %1 = 3 + 4
        // %2 = 3 + 5    → verschillend, dus beide bewaard
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(3);
            final var c1 = b.constInt(4);
            final var c2 = b.constInt(5);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, c1);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, c2);
            b.emitReturn(b.constInt(0));
        });

        final var function = module.functions().getFirst();
        assertFalse(new GlobalValueNumbering().run(function),
                "Verschillende expressies → geen wijzigingen");
    }

    @Test
    void testSameOperandsDifferentOrder() {
        // %1 = 3 + 4
        // %2 = 4 + 3    → commutatief maar verschillende hash (niet herkend door eenvoudige GVN)
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(3);
            final var c = b.constInt(4);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, c);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, c, a);
            b.emitReturn(b.constInt(0));
        });

        final var function = module.functions().getFirst();
        // Basis GVN herkent geen commutativiteit
        assertFalse(new GlobalValueNumbering().run(function),
                "Basis GVN herkent geen commutatieve equivalentie");
    }

    @Test
    void testOperandsFromOutsideTheBlockAreNotConfused() {
        // %1 = min < 0
        // %2 = max < 0  → min en max zijn parameters (niet in dit blok
        //                 gedefinieerd); ze kregen allebei value number 0, dus
        //                 werd %2 = %1 en las picocli's Range `max < 0` als `min < 0`.
        final var min = new IRValue.Temp("min", IRType.I32);
        final var max = new IRValue.Temp("max", IRType.I32);
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(min, max), 0);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, min, b.constInt(0));
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, max, b.constInt(0));
            b.emitReturn(b.constInt(0));
        });

        assertFalse(new GlobalValueNumbering().run(module.functions().getFirst()),
                "Expressies over verschillende parameters mogen niet samenvallen");
    }

    @Test
    void testSameOutsideOperandIsStillShared() {
        final var x = new IRValue.Temp("x", IRType.I32);
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(x), 0);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, x, b.constInt(1));
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, x, b.constInt(1));
            b.emitReturn(b.constInt(0));
        });

        assertTrue(new GlobalValueNumbering().run(module.functions().getFirst()),
                "x + 1 tweemaal over dezelfde parameter is wél dezelfde waarde");
    }

    @Test
    void testNoBinaryOps() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            b.emitReturn(b.constInt(42));
        });

        final var function = module.functions().getFirst();
        assertFalse(new GlobalValueNumbering().run(function),
                "Geen BinaryOps → geen wijzigingen");
    }
}

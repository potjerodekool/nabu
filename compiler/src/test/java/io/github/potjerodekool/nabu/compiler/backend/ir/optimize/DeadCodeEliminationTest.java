package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.optimize.DeadCodeElimination;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DeadCodeEliminationTest {

    private long countType(IRModule module, Class<? extends IRInstruction> type) {
        return module.functions().getFirst().blocks().stream()
                .flatMap(b -> b.instructions().stream())
                .filter(type::isInstance)
                .count();
    }

    private long totalInstructions(IRModule module) {
        return module.functions().getFirst().blocks().stream()
                .mapToLong(b -> b.instructions().size())
                .sum();
    }

    private IRModule buildModule(Consumer<IRBuilder> builder) {
        final var b = new IRBuilder("test");
        b.setLocation("test.lang", 1, 1);
        builder.accept(b);
        b.endFunction();
        return b.build();
    }

    @Test
    void testRemoveUnusedBinaryOp() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, b.constInt(1), b.constInt(2));
            b.emitReturn(b.constInt(0));
        });

        final var function = module.functions().getFirst();
        final var before = totalInstructions(module);

        new DeadCodeElimination().run(function);

        final var after = totalInstructions(module);
        assertEquals(before - 1, after, "Dode BinaryOp verwijderd");
        assertEquals(0, countType(module, IRInstruction.BinaryOp.class),
                "Geen BinaryOps meer (ongebruikt resultaat)");
    }

    @Test
    void testKeepUsedBinaryOp() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var sum = b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, b.constInt(1), b.constInt(2));
            b.emitReturn(sum);
        });

        final var function = module.functions().getFirst();
        assertFalse(new DeadCodeElimination().run(function),
                "Geen wijzigingen als alles gebruikt wordt");
    }

    @Test
    void testRemoveUnusedLoad() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.VOID, List.of(), 0);
            final var ptr = b.emitAlloca("x", IRType.I32);
            b.emitLoad(ptr);
            b.emitReturn(null);
        });

        final var function = module.functions().getFirst();
        new DeadCodeElimination().run(function);

        assertEquals(0, countType(module, IRInstruction.Load.class),
                "Dode Load verwijderd");
    }

    @Test
    void testKeepUsedLoad() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var ptr = b.emitAlloca("x", IRType.I32);
            final var val = b.emitLoad(ptr);
            b.emitReturn(val);
        });

        final var function = module.functions().getFirst();
        assertFalse(new DeadCodeElimination().run(function),
                "Load wordt gebruikt → niet verwijderen");
    }

    @Test
    void testRemoveUnusedMove() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(42);
            b.emitMove(IRType.I32, a);
            b.emitReturn(b.constInt(0));
        });

        final var function = module.functions().getFirst();
        new DeadCodeElimination().run(function);

        assertEquals(0, countType(module, IRInstruction.Move.class),
                "Dode Move verwijderd");
    }

    @Test
    void testKeepEffectfulInstructions() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.VOID, List.of(), 0);
            final var ptr = b.emitAlloca("x", IRType.I32);
            b.emitStore(ptr, b.constInt(42));
            b.emitReturn(null);
        });

        final var function = module.functions().getFirst();
        assertFalse(new DeadCodeElimination().run(function),
                "Store/Alloca hebben side-effects → niet verwijderen");
    }

    @Test
    void testChainOfDeadCode() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            // %1 = 1 + 2  (dead)
            final var a = b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, b.constInt(1), b.constInt(2));
            // %2 = %1 + 3 (dead als %2 niet gebruikt wordt)
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, b.constInt(3));
            b.emitReturn(b.constInt(0));
        });

        final var function = module.functions().getFirst();
        new DeadCodeElimination().run(function);

        // Na de eerste iteratie: %2 = %1 + 3 wordt verwijderd
        // Na de tweede iteratie: %1 = 1 + 2 wordt ook verwijderd
        assertEquals(0, countType(module, IRInstruction.BinaryOp.class),
                "Beide dode BinaryOps verwijderd (iteratief)");
    }
}

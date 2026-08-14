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

class TypeCheckerTest {

    private IRModule buildModule(Consumer<IRBuilder> builder) {
        final var b = new IRBuilder("test");
        b.setLocation("test.lang", 1, 1);
        builder.accept(b);
        b.endFunction();
        return b.build();
    }

    @Test
    void testValidProgramNoErrors() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(10);
            final var r = b.constInt(20);
            final var sum = b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, r);
            b.emitReturn(sum);
        });

        final var result = TypeChecker.check(module);
        assertTrue(result.isOk(), "Geen fouten verwacht in geldig programma");
    }

    @Test
    void testReturnMismatch() {
        // Functie retourneert I32, maar we geven Bool
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            b.emitReturn(b.constBool(true));
        });

        final var result = TypeChecker.check(module);
        assertFalse(result.isOk(), "Fout verwacht bij return type mismatch");
        assertTrue(result.errors().getFirst().contains("Return"),
                "Foutmelding moet over return gaan");
    }

    @Test
    void testValidReturnVoid() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.VOID, List.of(), 0);
            b.emitReturn(null);
        });

        final var result = TypeChecker.check(module);
        assertTrue(result.isOk(), "Geen fouten bij void return");
    }

    @Test
    void testValidBinaryOp() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(5);
            final var r = b.constInt(3);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, a, r);
            b.emitReturn(b.constInt(0));
        });

        final var result = TypeChecker.check(module);
        assertTrue(result.isOk(), "Geen fouten bij geldige BinaryOp");
    }

    @Test
    void testValidAllocaLoadStore() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var ptr = b.emitAlloca("x", IRType.I32);
            b.emitStore(ptr, b.constInt(42));
            final var val = b.emitLoad(ptr);
            b.emitReturn(val);
        });

        final var result = TypeChecker.check(module);
        assertTrue(result.isOk(), "Geen fouten bij geldige alloca/load/store");
    }

    @Test
    void testValidPhi() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var entry = b.currentBlock();
            final var thenBlk = b.beginBlock("then");
            final var mergeBlk = b.beginBlock("merge");

            b.setCurrentBlock(entry);
            b.emitCondBranch(b.constBool(true), thenBlk, mergeBlk);

            b.setCurrentBlock(thenBlk);
            b.emitBranch(mergeBlk);

            b.setCurrentBlock(mergeBlk);
            final var v1 = b.constInt(10);
            final var v2 = b.constInt(20);
            b.emitPhi(IRType.I32, List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, entry)
            ));
            b.emitReturn(b.constInt(0));
        });

        final var result = TypeChecker.check(module);
        assertTrue(result.isOk(), "Geen fouten bij geldige Phi");
    }

    @Test
    void testMultipleErrors() {
        // Twee functies met fouten
        final var module = buildModule(b -> {
            // Functie 1: return mismatch
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            b.emitReturn(b.constBool(true));

            // Functie 2: ook return mismatch
            b.beginFunction("bar", IRType.VOID, List.of(), 0);
            b.emitReturn(b.constInt(42));
        });

        final var result = TypeChecker.check(module);
        assertFalse(result.isOk(), "Fouten verwacht");
        assertTrue(result.errors().size() >= 2, "Minstens 2 fouten verwacht");
    }
}

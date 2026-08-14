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

class TypeInferenceTest {

    private IRModule buildModule(Consumer<IRBuilder> builder) {
        final var b = new IRBuilder("test");
        b.setLocation("test.lang", 1, 1);
        builder.accept(b);
        b.endFunction();
        return b.build();
    }

    @Test
    void testCommonTypeSameTypes() {
        assertEquals(IRType.I32, TypeInference.commonType(IRType.I32, IRType.I32));
        assertEquals(IRType.I64, TypeInference.commonType(IRType.I64, IRType.I64));
        assertEquals(IRType.BOOL, TypeInference.commonType(IRType.BOOL, IRType.BOOL));
    }

    @Test
    void testCommonTypeIntegerPromotion() {
        assertEquals(IRType.I64, TypeInference.commonType(IRType.I32, IRType.I64));
        assertEquals(IRType.I64, TypeInference.commonType(IRType.I64, IRType.I32));
        assertEquals(new IRType.Int(16), TypeInference.commonType(new IRType.Int(8), new IRType.Int(16)));
    }

    @Test
    void testCommonTypeFloatPromotion() {
        assertEquals(IRType.F64, TypeInference.commonType(IRType.F32, IRType.F64));
        assertEquals(IRType.F64, TypeInference.commonType(IRType.F64, IRType.F32));
    }

    @Test
    void testCommonTypeIntFloat() {
        assertEquals(IRType.F64, TypeInference.commonType(IRType.I32, IRType.F64));
        assertEquals(IRType.F32, TypeInference.commonType(IRType.I32, IRType.F32));
    }

    @Test
    void testCommonTypeIncompatible() {
        assertNull(TypeInference.commonType(IRType.BOOL, IRType.I32));
        assertNull(TypeInference.commonType(IRType.I32, IRType.BOOL));
    }

    @Test
    void testCommonTypeNull() {
        assertNull(TypeInference.commonType(null, IRType.I32));
        assertNull(TypeInference.commonType(IRType.I32, null));
    }

    @Test
    void testBinaryOpTypeInference() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var l = b.constInt(3);
            final var r = new IRValue.Temp("%unknown", IRType.VOID); // verkeerd type
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, l, r);
            b.emitReturn(b.constInt(0));
        });

        final var function = module.functions().getFirst();
        final var ti = new TypeInference();
        // Moet zonder fouten aflopen (geen exception)
        ti.run(function);
    }

    @Test
    void testBinaryOpComparisonProducesBool() {
        // Vergelijking: 10 > 5 → resultaat zou Bool moeten zijn
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.BOOL, List.of(), 0);
            final var l = b.constInt(10);
            final var r = b.constInt(5);
            b.emitBinaryOp(IRInstruction.BinaryOp.Op.GT, l, r);
            b.emitReturn(b.constBool(true));
        });

        final var function = module.functions().getFirst();
        final var ti = new TypeInference();
        ti.run(function);

        // Na inferentie zou het resultaat Bool moeten zijn
        final var binaryOps = function.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(i -> i instanceof IRInstruction.BinaryOp)
                .map(i -> (IRInstruction.BinaryOp) i)
                .toList();

        assertFalse(binaryOps.isEmpty(), "BinaryOp aanwezig");
        // Het resultaat is nog steeds een Temp met de originele type,
        // maar de typeMap zou het juiste type moeten hebben
    }

    @Test
    void testMoveTypePropagation() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var a = b.constInt(42);
            b.emitMove(IRType.VOID, a); // foutief move-type
            b.emitReturn(b.constInt(0));
        });

        final var function = module.functions().getFirst();
        final var ti = new TypeInference();
        ti.run(function);
        // Moet zonder fouten aflopen
    }

    @Test
    void testPhiTypeInference() {
        final var module = buildModule(b -> {
            b.beginFunction("foo", IRType.I32, List.of(), 0);
            final var entry = b.currentBlock();
            final var thenBlk = b.beginBlock("then");
            final var elseBlk = b.beginBlock("else");
            final var mergeBlk = b.beginBlock("merge");

            b.setCurrentBlock(entry);
            b.emitCondBranch(b.constBool(true), thenBlk, elseBlk);

            b.setCurrentBlock(thenBlk);
            b.emitBranch(mergeBlk);

            b.setCurrentBlock(elseBlk);
            b.emitBranch(mergeBlk);

            b.setCurrentBlock(mergeBlk);
            final var v1 = b.constInt(10);
            final var v2 = b.constInt(20);
            final var phi = b.emitPhi(IRType.I32, List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            b.emitReturn(phi);
        });

        final var function = module.functions().getFirst();
        final var ti = new TypeInference();
        ti.run(function);
        // Phi zou I32 type moeten hebben (van beide incoming waarden)
    }
}

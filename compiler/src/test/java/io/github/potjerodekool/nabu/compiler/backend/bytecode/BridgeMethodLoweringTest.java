package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Een bridge-methode die alleen het returntype verandert, deelt naam en
 * parameters met haar doel. {@code ByteBuffer} heeft naast
 * {@code ByteBuffer limit(int)} de bridge {@code Buffer limit(int)}; de backend
 * sleutelt functies op naam + parameters, dus beide bodies belandden in één
 * LLVM-functie. De bridge kwam eerst en riep virtueel zichzelf aan: een
 * eindeloze lus in {@code HeapByteBuffer.<init>} (System.out-opbouw).
 */
class BridgeMethodLoweringTest {

    @Test
    void returnOnlyBridgeIsReplacedByItsTarget() {
        final var compiler = new BytecodeReachabilityCompiler(null, Set.of());
        final List<IRModule> modules = compiler.compileClosure(List.of("java/nio/ByteBuffer"));

        final IRModule byteBuffer = modules.stream()
                .filter(m -> m.name.replace('.', '/').equals("java/nio/ByteBuffer"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ByteBuffer niet gelowered"));

        final List<IRFunction> limitInt = byteBuffer.functions().stream()
                .filter(f -> f.name.equals("java.nio.ByteBuffer_limit") && f.params.size() == 2)
                .toList();

        assertEquals(1, limitInt.size(), "precies één limit(int), geen bridge ernaast");
        final IRType returnType = limitInt.getFirst().returnType;
        assertEquals("Ljava/nio/ByteBuffer;",
                returnType instanceof IRType.Ptr p ? p.jvmDescriptor() : String.valueOf(returnType),
                "het covariante doel, niet de bridge die Buffer teruggeeft");
    }
}

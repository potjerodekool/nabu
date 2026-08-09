package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AsmHelperTest {

    @Test
    void createDescriptorWithValues() {
        final var descriptor = AsmHelper.createDescriptorWithValues(
                List.of(
                        new IRValue.Temp("value", IRType.I8)
                ),
                IRType.BOOL
        );
        assertEquals("(B)Z", descriptor);
    }

    @Test
    void createDescriptor() {
    }

    @Test
    void testCreateDescriptor() {
    }

    @Test
    void toInternalName() {
    }

    @Test
    void testCreateDescriptor1() {
    }

    @Test
    void testToInternalName() {
        final var actual = AsmHelper.toInternalName(
                new IRType.Ptr(
                        IRType.I8,
                        "Ljava/lang/Integer;"
                )
        );

        assertEquals("java/lang/Integer", actual);
    }
}
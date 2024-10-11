package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
class AsmUtilsTest {

    @Test
    void getMethodSignature() {
        final var returnType =
                IReferenceType.create(
                        "JpaPredicate",
                        List.of(IReferenceType.create("Person"))
                        );
        final var signature = AsmUtils.getMethodSignature(List.of(IPrimitiveType.INT), returnType);

        assertEquals("(I)LJpaPredicate<LPerson;>;", signature);
    }

    @Test
    void getMethodSignatureNot() {
        final var returnType =
                IReferenceType.create("JpaPredicate");
        final var signature = AsmUtils.getMethodSignature(List.of(IPrimitiveType.INT), returnType);

        assertNull(signature);
    }

    @Test
    void getMethodSignatureWithVoid() {
        final var returnType = IPrimitiveType.VOID;
        final var parameterType = IReferenceType.create(
                "JpaPredicate",
                List.of(IReferenceType.create("Person"))
        );

        final var signature = AsmUtils.getMethodSignature(List.of(parameterType), returnType);

        assertEquals("(LJpaPredicate<LPerson;>;)V", signature);
    }
}
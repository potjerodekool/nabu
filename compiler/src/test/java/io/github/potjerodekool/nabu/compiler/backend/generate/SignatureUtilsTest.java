package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.SymbolTable;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignatureUtilsTest {

    private final ClassElementLoader loader = new AsmClassElementLoader(new SymbolTable());
    private boolean init = true;

    @BeforeEach
    void setup() {
        if (init) {
            loader.postInit();
            init = false;
        }
    }

    @Test
    void getMethodSignature() {
        final var returnType =
                IReferenceType.createClassType(
                        null,
                        "JpaPredicate",
                        List.of(IReferenceType.createClassType( null,"Person", List.of()))
                );
        final var signature = AsmISignatureGenerator.INSTANCE.getMethodSignature(List.of(IPrimitiveType.INT), returnType);

        assertEquals("(I)LJpaPredicate<LPerson;>;", signature);
    }

    @Test
    void getMethodSignatureNot() {
        final var returnType =
                IReferenceType.createClassType(null,"JpaPredicate", List.of());
        final var signature = AsmISignatureGenerator.INSTANCE.getMethodSignature(List.of(IPrimitiveType.INT), returnType);
        assertEquals("(I)LJpaPredicate;", signature);
    }

    @Test
    void getMethodSignatureWithVoid() {
        final var returnType = IPrimitiveType.VOID;
        final var parameterType = IReferenceType.createClassType(
                null,
                "JpaPredicate",
                List.of(IReferenceType.createClassType( null, "Person", List.of()))
        );

        final var signature = AsmISignatureGenerator.INSTANCE.getMethodSignature(List.of(parameterType), returnType);

        assertEquals("(LJpaPredicate<LPerson;>;)V", signature);
    }

}

package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.NabuCFileManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignatureUtilsTest {

    private CompilerContext compilerContext;

    @BeforeEach
    void setup() {
        if (compilerContext == null) {
            this.compilerContext = new CompilerContextImpl(
                    new ApplicationContext(),
                    new NabuCFileManager()
            );
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

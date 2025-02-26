package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.ITypeKind;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.SymbolTable;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignatureUtilsTest {

    private final ClassElementLoader loader = new AsmClassElementLoader(new SymbolTable());
    private final Types types = loader.getTypes();
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
                IReferenceType.create(
                        ITypeKind.CLASS,
                        "JpaPredicate",
                        List.of(IReferenceType.create(ITypeKind.CLASS,"Person", List.of()))
                );
        final var signature = SignatureUtils.getMethodSignature(List.of(IPrimitiveType.INT), returnType);

        assertEquals("(I)LJpaPredicate<LPerson;>;", signature);
    }

    @Test
    void getMethodSignatureNot() {
        final var returnType =
                IReferenceType.create(ITypeKind.CLASS,"JpaPredicate", List.of());
        final var signature = SignatureUtils.getMethodSignature(List.of(IPrimitiveType.INT), returnType);
        assertEquals("(I)LJpaPredicate;", signature);
    }

    @Test
    void getMethodSignatureWithVoid() {
        final var returnType = IPrimitiveType.VOID;
        final var parameterType = IReferenceType.create(
                ITypeKind.CLASS,
                "JpaPredicate",
                List.of(IReferenceType.create(ITypeKind.CLASS,"Person", List.of()))
        );

        final var signature = SignatureUtils.getMethodSignature(List.of(parameterType), returnType);

        assertEquals("(LJpaPredicate<LPerson;>;)V", signature);
    }

    @Test
    void getClassSignature() {
        final var expected = "<E:Ljava/lang/Object;:Ljava/lang/Comparable;>Ljava/lang/Object;";

        /*
            <<E:Ljava/lang/Object;:Ljava/lang/Comparable     ;>Ljava/lang/Object;> but was:
            <<E:Ljava/lang/Object;:Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;>
         */

        final var typeParameters = List.of(
                (TypeParameterElement) types.getTypeVariable(
                        "E",
                        types.getIntersectionType(
                                List.of(
                                        types.getDeclaredType(loader.resolveClass("java.lang.Object")),
                                        types.getDeclaredType(loader.resolveClass("java.lang.Comparable"))
                                )
                        ),
                        null
                ).asElement()
        );


        final var actual = SignatureUtils.getClassSignature(
                typeParameters,
                loader.resolveClass("java.lang.Object").asType()
        );

        assertEquals(expected, actual);
    }

    @Test
    void getClassSignatureAllInterfaces() {
        final var expected = "<E::Ljava/util/Iterator<TE;>;:Ljava/lang/Comparable;B:Ljava/lang/Object;>Ljava/lang/Object;";

        final var typeParameters = Stream.of(
                        types.getTypeVariable(
                                "E",
                                types.getIntersectionType(
                                        List.of(
                                                types.getDeclaredType(
                                                        loader.resolveClass("java.util.Iterator"),
                                                        types.getTypeVariable("E", loader.resolveClass("java.lang.Object").asType(), null)
                                                ),
                                                types.getDeclaredType(
                                                        loader.resolveClass("java.lang.Comparable")
                                                )
                                        )
                                ),
                                null
                        ),
                        types.getTypeVariable(
                                "B",
                                types.getDeclaredType(loader.resolveClass("java.lang.Object")),
                                null
                        )
                )
                .map(it -> (TypeParameterElement) it.asElement())
                .toList();

        final var supertype = types.getDeclaredType(loader.resolveClass("java.lang.Object"));

        final var actual = SignatureUtils.getClassSignature(
                typeParameters,
                supertype
        );

        assertEquals(expected, actual);
    }

}

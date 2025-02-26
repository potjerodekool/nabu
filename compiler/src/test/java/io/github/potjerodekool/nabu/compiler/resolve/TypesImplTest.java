package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypesImplTest {

    private final ClassElementLoader loader = new AsmClassElementLoader();
    private final Types types = loader.getTypes();

    @Test
    void asMemberOf() {
        final var setClass = loader.resolveClass("java.util.Set");
        final var objectType = loader.resolveClass(Constants.OBJECT).asType();

        final var stringClass = loader.resolveClass(Constants.STRING);

        final var stringType = types.getDeclaredType(
                null,
                stringClass
        );

        final var stringSetType = types.getDeclaredType(
                null,
                setClass,
                stringType
        );

        final var intType = types.getPrimitiveType(TypeKind.INT);

        final var method = new MethodBuilder()
                .name("add")
                .enclosingElement(setClass)
                .returnType(types.getNoType(TypeKind.VOID))
                .argumentTypes(List.of(intType, types.getTypeVariable("E",objectType, null)))
                .build();

        final var methodType = types.asMemberOf(
                stringSetType,
                method
        );

        final var printer = new TypePrinter();
        methodType.accept(printer, null);
        final var actual = printer.getText();

        assertEquals("void (int, java.lang.String)", actual);
    }

    @Test
    void isSubTypePrimitive() {
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.FLOAT), types.getPrimitiveType(TypeKind.DOUBLE)));
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.LONG), types.getPrimitiveType(TypeKind.FLOAT)));
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.INT), types.getPrimitiveType(TypeKind.LONG)));
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.CHAR), types.getPrimitiveType(TypeKind.INT)));
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.SHORT), types.getPrimitiveType(TypeKind.INT)));
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.BYTE), types.getPrimitiveType(TypeKind.SHORT)));
    }

    @Test
    void isSubTypeNonGeneric() {
        final var arrayListType = (DeclaredType) loader.resolveClass("java.util.ArrayList").asType();
        final var listType = (DeclaredType) loader.resolveClass("java.util.List").asType();
        assertTrue(types.isSubType(arrayListType, listType));
    }

    @Test
    void isSubTypeGeneric() {
        final var arrayListType = (DeclaredType) loader.resolveClass("java.util.ArrayList").asType();
        final var listType = (DeclaredType) loader.resolveClass("java.util.List").asType();
        final var objectType = (DeclaredType) loader.resolveClass("java.lang.Object").asType();
        final var stringType = (DeclaredType) loader.resolveClass("java.lang.String").asType();

        final var arrayListOfObjectType = types.getDeclaredType(
                (TypeElement) arrayListType.asElement(),
                objectType
        );

        final var listOfStringType = types.getDeclaredType(
                (TypeElement) listType.asElement(),
                stringType
        );

        final var arrayListOfStringType = types.getDeclaredType(
                (TypeElement) arrayListType.asElement(),
                stringType
        );

        assertFalse(types.isSubType(
                arrayListOfObjectType,
                listOfStringType
        ));

        assertTrue(types.isSubType(
                arrayListOfStringType,
                listOfStringType
        ));
    }

    @Test
    void isSubTypeArray() {
        final var objectType = loader.resolveClass("java.lang.Object").asType();
        final var stringType = loader.resolveClass("java.lang.String").asType();
        final var cloneableType = loader.resolveClass(Constants.CLONEABLE).asType();
        final var serializableType = loader.resolveClass(Constants.SERIALIZABLE).asType();
        final var intType = types.getPrimitiveType(TypeKind.INT);

        assertTrue(types.isSubType(
                types.getArrayType(stringType),
                types.getArrayType(objectType)
        ));

        assertTrue(
                types.isSubType(
                        types.getArrayType(stringType),
                        cloneableType
                )
        );

        assertTrue(
                types.isSubType(
                        types.getArrayType(stringType),
                        serializableType
                )
        );

        assertTrue(
                types.isSubType(
                        types.getArrayType(intType),
                        objectType
                )
        );

        assertTrue(
                types.isSubType(
                        types.getArrayType(intType),
                        cloneableType
                )
        );

        assertTrue(
                types.isSubType(
                        types.getArrayType(intType),
                        serializableType
                )
        );
    }


}

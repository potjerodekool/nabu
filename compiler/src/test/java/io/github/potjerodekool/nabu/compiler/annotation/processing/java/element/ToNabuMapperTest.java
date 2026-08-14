package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.JPrimitiveType;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.compiler.type.impl.*;
import io.github.potjerodekool.nabu.type.BoundKind;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.lang.model.type.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToNabuMapperTest extends AbstractCompilerTest {
    // ---------------------------------------------------------------
    // Primitive types
    // ---------------------------------------------------------------

    @Test
    void visitPrimitive_int() {
        final var nabuType = new CPrimitiveType(TypeKind.INT);
        final var input = new JPrimitiveType(javax.lang.model.type.TypeKind.INT, nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.INT, result.getKind());
    }

    @Test
    void visitPrimitive_long() {
        final var nabuType = new CPrimitiveType(TypeKind.LONG);
        final var input = new JPrimitiveType(javax.lang.model.type.TypeKind.LONG, nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.LONG, result.getKind());
    }

    @Test
    void visitPrimitive_short() {
        final var nabuType = new CPrimitiveType(TypeKind.SHORT);
        final var input = new JPrimitiveType(javax.lang.model.type.TypeKind.SHORT, nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.SHORT, result.getKind());
    }

    @Test
    void visitPrimitive_byte() {
        final var nabuType = new CPrimitiveType(TypeKind.BYTE);
        final var input = new JPrimitiveType(javax.lang.model.type.TypeKind.BYTE, nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.BYTE, result.getKind());
    }

    @Test
    void visitPrimitive_char() {
        final var nabuType = new CPrimitiveType(TypeKind.CHAR);
        final var input = new JPrimitiveType(javax.lang.model.type.TypeKind.CHAR, nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.CHAR, result.getKind());
    }

    @Test
    void visitPrimitive_float() {
        final var nabuType = new CPrimitiveType(TypeKind.FLOAT);
        final var input = new JPrimitiveType(javax.lang.model.type.TypeKind.FLOAT, nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.FLOAT, result.getKind());
    }

    @Test
    void visitPrimitive_double() {
        final var nabuType = new CPrimitiveType(TypeKind.DOUBLE);
        final var input = new JPrimitiveType(javax.lang.model.type.TypeKind.DOUBLE, nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.DOUBLE, result.getKind());
    }

    @Test
    void visitPrimitive_boolean() {
        final var nabuType = new CPrimitiveType(TypeKind.BOOLEAN);
        final var input = new JPrimitiveType(javax.lang.model.type.TypeKind.BOOLEAN, nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.BOOLEAN, result.getKind());
    }

    // ---------------------------------------------------------------
    // Null type
    // ---------------------------------------------------------------

    @Test
    void visitNull() {
        final var nabuType = new CNullType();
        final var input = (NullType) TypeWrapperFactory.wrap(nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.NULL, result.getKind());
    }

    // ---------------------------------------------------------------
    // NoType (void / none)
    // ---------------------------------------------------------------

    @Test
    void visitNoType_void() {
        final var nabuType = new CVoidType();
        final var input = (NoType) TypeWrapperFactory.wrap(nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.VOID, result.getKind());
    }

    @Test
    void visitNoType_none() {
        final var nabuType = new CNoType();
        final var input = (NoType) TypeWrapperFactory.wrap(nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.NONE, result.getKind());
    }

    // ---------------------------------------------------------------
    // Declared type
    // ---------------------------------------------------------------

    @Test
    void visitDeclared_string() {
        final var module = getCompilerContext().getModules().getJavaBase();
        final var stringClass = getCompilerContext()
                .getClassElementLoader().loadClass(module, "java.lang.String");
        final var nabuDeclared = (io.github.potjerodekool.nabu.type.DeclaredType) stringClass.asType();
        final var input = (DeclaredType) TypeWrapperFactory.wrap(nabuDeclared);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.DECLARED, result.getKind());
    }

    @Test
    void visitDeclared_listOfInteger() {
        final var module = getCompilerContext().getModules().getJavaBase();
        final var listClass = getCompilerContext()
                .getClassElementLoader().loadClass(module, "java.util.List");
        final var integerClass = getCompilerContext()
                .getClassElementLoader().loadClass(module, "java.lang.Integer");

        final var types = getCompilerContext().getTypes();
        final var nabuDeclared = types.getDeclaredType(listClass, integerClass.asType());
        final var input = (DeclaredType) TypeWrapperFactory.wrap(nabuDeclared);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.DECLARED, result.getKind());
    }

    // ---------------------------------------------------------------
    // Wildcard type
    // ---------------------------------------------------------------

    @Test
    void visitWildcard_extends() {
        final var nabuWildcard = new CWildcardType(null, BoundKind.EXTENDS, null);
        final var input = (WildcardType) TypeWrapperFactory.wrap(nabuWildcard);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.WILDCARD, result.getKind());
    }

    @Test
    void visitWildcard_super() {
        final var nabuWildcard = new CWildcardType(null, BoundKind.SUPER, null);
        final var input = (WildcardType) TypeWrapperFactory.wrap(nabuWildcard);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.WILDCARD, result.getKind());
    }

    @Test
    void visitWildcard_unbound() {
        final var nabuWildcard = new CWildcardType(null, BoundKind.UNBOUND, null);
        final var input = (WildcardType) TypeWrapperFactory.wrap(nabuWildcard);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.WILDCARD, result.getKind());
    }

    // ---------------------------------------------------------------
    // Type variable
    // ---------------------------------------------------------------

    @Test
    void visitTypeVariable() {
        final var nabuTypeVar = new CTypeVariable("T", null, null, null);
        final var input = (TypeVariable) TypeWrapperFactory.wrap(nabuTypeVar);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.TYPEVAR, result.getKind());
    }

    // ---------------------------------------------------------------
    // Executable type
    // ---------------------------------------------------------------

    @Test
    void visitExecutable_voidReturnNoParams() {
        final var nabuExecutable = new CMethodType(
                null,
                null,
                List.of(),
                new CVoidType(),
                List.of(),
                List.of()
        );
        final var input = (ExecutableType) TypeWrapperFactory.wrap(nabuExecutable);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.EXECUTABLE, result.getKind());
    }

    @Test
    void visitExecutable_withReturnTypeAndParams() {
        final var nabuExecutable = new CMethodType(
                null,
                null,
                List.of(),
                new CPrimitiveType(TypeKind.INT),
                List.of(new CPrimitiveType(TypeKind.INT)),
                List.of()
        );
        final var input = (ExecutableType) TypeWrapperFactory.wrap(nabuExecutable);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.EXECUTABLE, result.getKind());
    }

    // ---------------------------------------------------------------
    // visit() — generic dispatch entry point
    // ---------------------------------------------------------------

    @Test
    void visit_dispatchesToPrimitive() {
        final var nabuType = new CPrimitiveType(TypeKind.INT);
        final var input = new JPrimitiveType(javax.lang.model.type.TypeKind.INT, nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.INT, result.getKind());
    }

    @Test
    void visit_dispatchesToNull() {
        final var nabuType = new CNullType();
        final var input = TypeWrapperFactory.wrap(nabuType);

        final var result = ToNabuMapper.accept(input);

        assertNotNull(result);
        assertEquals(TypeKind.NULL, result.getKind());
    }

    // ---------------------------------------------------------------
    // Identity preservation — wrapped Nabu types survive the round-trip
    // ---------------------------------------------------------------

    @Test
    void primitiveMapping_preservesOriginal() {
        final var nabuType = new CPrimitiveType(TypeKind.BOOLEAN);
        final var input = new JPrimitiveType(javax.lang.model.type.TypeKind.BOOLEAN, nabuType);

        final var result = ToNabuMapper.accept(input);

        assertSame(nabuType, result);
    }

    @Test
    void nullMapping_preservesOriginal() {
        final var nabuType = new CNullType();
        final var input = (NullType) TypeWrapperFactory.wrap(nabuType);

        final var result = ToNabuMapper.accept(input);

        assertSame(nabuType, result);
    }

    @Test
    void typeVariable_preservesOriginal() {
        final var nabuTypeVar = new CTypeVariable("T", null, null, null);
        final var input = (TypeVariable) TypeWrapperFactory.wrap(nabuTypeVar);

        final var result = ToNabuMapper.accept(input);

        assertSame(nabuTypeVar, result);
    }
}

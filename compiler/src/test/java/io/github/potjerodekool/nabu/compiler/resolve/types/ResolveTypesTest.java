package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.*;
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.Modifier;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.type.BoundKind;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResolveTypesTest extends AbstractCompilerTest {

    private Types types;
    private TypesImpl typesImpl;
    private IsSubType isSubType;

    @BeforeEach
    void setUp() {
        types = getCompilerContext().getTypes();
        typesImpl = (TypesImpl) types;
        isSubType = new IsSubType(typesImpl);
    }

    private MethodSymbol createMethodSymbol(final String name) {
        final var classSymbol = new ClassSymbol(0, "TestClass", null);
        return new MethodSymbol(
                ElementKind.METHOD, 0, name, classSymbol,
                null, List.of(),
                new CPrimitiveType(TypeKind.INT),
                List.of(), List.of(), List.of()
        );
    }

    // ========================= IsSameType.visitMethodType =========================

    @Test
    void isSameType_methodType_sameTypes() {
        final var isSameType = new IsSameType();
        final var symbol1 = createMethodSymbol("m1");
        final var symbol2 = createMethodSymbol("m2");
        final var intType = new CPrimitiveType(TypeKind.INT);
        final var boolType = new CPrimitiveType(TypeKind.BOOLEAN);

        final var a = new CMethodType(symbol1, null, List.of(),
                intType, List.of(boolType), List.of());
        final var b = new CMethodType(symbol2, null, List.of(),
                intType, List.of(boolType), List.of());

        assertTrue(isSameType.visitMethodType(a, b));
    }

    @Test
    void isSameType_methodType_differentParamCount() {
        final var isSameType = new IsSameType();
        final var symbol1 = createMethodSymbol("m1");
        final var symbol2 = createMethodSymbol("m2");
        final var intType = new CPrimitiveType(TypeKind.INT);

        final var a = new CMethodType(symbol1, null, List.of(),
                intType, List.of(intType), List.of());
        final var b = new CMethodType(symbol2, null, List.of(),
                intType, List.of(intType, intType), List.of());

        assertFalse(isSameType.visitMethodType(a, b));
    }

    @Test
    void isSameType_methodType_differentParamType() {
        final var isSameType = new IsSameType();
        final var symbol1 = createMethodSymbol("m1");
        final var symbol2 = createMethodSymbol("m2");
        final var intType = new CPrimitiveType(TypeKind.INT);
        final var boolType = new CPrimitiveType(TypeKind.BOOLEAN);

        final var a = new CMethodType(symbol1, null, List.of(),
                intType, List.of(intType), List.of());
        final var b = new CMethodType(symbol2, null, List.of(),
                intType, List.of(boolType), List.of());

        assertFalse(isSameType.visitMethodType(a, b));
    }

    @Test
    void isSameType_methodType_differentReturnType() {
        final var isSameType = new IsSameType();
        final var symbol1 = createMethodSymbol("m1");
        final var symbol2 = createMethodSymbol("m2");
        final var intType = new CPrimitiveType(TypeKind.INT);
        final var boolType = new CPrimitiveType(TypeKind.BOOLEAN);

        final var a = new CMethodType(symbol1, null, List.of(),
                intType, List.of(), List.of());
        final var b = new CMethodType(symbol2, null, List.of(),
                boolType, List.of(), List.of());

        assertFalse(isSameType.visitMethodType(a, b));
    }

    @Test
    void isSameType_methodType_nullReturnTypes() {
        final var isSameType = new IsSameType();
        final var symbol1 = createMethodSymbol("m1");
        final var symbol2 = createMethodSymbol("m2");

        final var a = new CMethodType(symbol1, null, List.of(),
                null, List.of(), List.of());
        final var b = new CMethodType(symbol2, null, List.of(),
                null, List.of(), List.of());

        assertTrue(isSameType.visitMethodType(a, b));
    }

    @Test
    void isSameType_methodType_oneNullReturnType() {
        final var isSameType = new IsSameType();
        final var symbol1 = createMethodSymbol("m1");
        final var symbol2 = createMethodSymbol("m2");
        final var intType = new CPrimitiveType(TypeKind.INT);

        final var a = new CMethodType(symbol1, null, List.of(),
                null, List.of(), List.of());
        final var b = new CMethodType(symbol2, null, List.of(),
                intType, List.of(), List.of());

        assertFalse(isSameType.visitMethodType(a, b));
    }

    @Test
    void isSameType_methodType_notExecutableType() {
        final var isSameType = new IsSameType();
        final var symbol = createMethodSymbol("m");
        final var intType = new CPrimitiveType(TypeKind.INT);

        final var a = new CMethodType(symbol, null, List.of(),
                intType, List.of(), List.of());

        assertFalse(isSameType.visitMethodType(a, intType));
    }

    // ========================= ContainsType =========================

    @Test
    void containsType_getUpperBound_nonWildcardType() {
        final var containsType = new ContainsType(isSubType);
        final var intType = new CPrimitiveType(TypeKind.INT);

        assertEquals(intType, containsType.getUpperBound(intType));
    }

    @Test
    void containsType_getUpperBound_superBoundWildcard() {
        final var containsType = new ContainsType(isSubType);
        final var intType = new CPrimitiveType(TypeKind.INT);
        final var wildcard = new CWildcardType(intType, BoundKind.SUPER, null);

        assertEquals(intType, containsType.getUpperBound(wildcard));
    }

    @Test
    void containsType_getUpperBound_extendsBoundWildcard() {
        final var containsType = new ContainsType(isSubType);
        final var intType = new CPrimitiveType(TypeKind.INT);
        final var wildcard = new CWildcardType(intType, BoundKind.EXTENDS, null);

        assertEquals(intType, containsType.getUpperBound(wildcard));
    }

    @Test
    void containsType_getUpperBound_unboundWildcard() {
        final var containsType = new ContainsType(isSubType);
        final var wildcard = new CWildcardType(null, BoundKind.UNBOUND, null);

        assertNull(containsType.getUpperBound(wildcard));
    }

    @Test
    void containsType_visitWildcardType_sameWildcard() {
        final var containsType = new ContainsType(isSubType);
        final var intType = new CPrimitiveType(TypeKind.INT);
        final var wildcard1 = new CWildcardType(intType, BoundKind.EXTENDS, null);
        final var wildcard2 = new CWildcardType(intType, BoundKind.EXTENDS, null);

        assertTrue(containsType.visitWildcardType(wildcard1, wildcard2));
    }

    @Test
    void containsType_visitWildcardType_extendsDoesNotContainUnrelatedType() {
        final var containsType = new ContainsType(isSubType);
        final var numberClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.Number");
        numberClass.complete();
        final var stringClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.String");
        stringClass.complete();

        final var wildcardExtendsNumber = new CWildcardType(numberClass.asType(), BoundKind.EXTENDS, null);
        final var wildcardExtendsString = new CWildcardType(stringClass.asType(), BoundKind.EXTENDS, null);

        assertFalse(containsType.visitWildcardType(wildcardExtendsNumber, wildcardExtendsString));
    }

    @Test
    void containsType_visitUnknownType() {
        final var containsType = new ContainsType(isSubType);
        final var intType = new CPrimitiveType(TypeKind.INT);

        assertFalse(containsType.visitUnknownType(intType, intType));
    }

    // ========================= MemberType =========================

    @Test
    void memberType_staticElement() {
        final var memberType = new MemberType();
        final var classSymbol = new ClassSymbol(Flags.STATIC, "TestClass", null);
        final var methodSymbol = new MethodSymbol(
                ElementKind.METHOD, Flags.STATIC, "staticMethod", classSymbol,
                null, List.of(),
                new CPrimitiveType(TypeKind.INT),
                List.of(), List.of(), List.of()
        );

        final var intType = new CPrimitiveType(TypeKind.INT);
        final var result = memberType.memberType(intType, methodSymbol);

        assertEquals(methodSymbol.asType(), result);
    }

    @Test
    void memberType_nonStaticElement() {
        final var memberType = new MemberType();
        final var classSymbol = new ClassSymbol(0, "TestClass", null);
        final var methodSymbol = new MethodSymbol(
                ElementKind.METHOD, 0, "instanceMethod", classSymbol,
                null, List.of(),
                new CPrimitiveType(TypeKind.INT),
                List.of(), List.of(), List.of()
        );

        final var intType = new CPrimitiveType(TypeKind.INT);
        final var result = memberType.memberType(intType, methodSymbol);

        assertNotNull(result);
    }

    @Test
    void memberType_visitUnknownType() {
        final var memberType = new MemberType();
        final var classSymbol = new ClassSymbol(0, "TestClass", null);
        final var methodSymbol = new MethodSymbol(
                ElementKind.METHOD, 0, "method", classSymbol,
                null, List.of(),
                new CPrimitiveType(TypeKind.INT),
                List.of(), List.of(), List.of()
        );

        final var unknownType = new CUnknownType();
        final var result = memberType.visitUnknownType(unknownType, methodSymbol);

        assertSame(unknownType, result);
    }
}

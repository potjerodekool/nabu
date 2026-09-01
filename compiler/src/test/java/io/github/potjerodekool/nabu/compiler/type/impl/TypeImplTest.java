package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.*;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.type.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TypeImplTest {

    private static final AtomicReference<String> VISITED_METHOD = new AtomicReference<>();

    private static final TypeVisitor<String, Void> TEST_VISITOR = new TypeVisitor<>() {
        @Override
        public String visitUnknownType(TypeMirror typeMirror, Void param) {
            VISITED_METHOD.set("visitUnknownType");
            return "unknown";
        }

        @Override
        public String visitNullType(NullType nullType, Void param) {
            VISITED_METHOD.set("visitNullType");
            return "null";
        }

        @Override
        public String visitNoType(NoType noType, Void param) {
            VISITED_METHOD.set("visitNoType");
            return "no";
        }

        @Override
        public String visitVariableType(VariableType variableType, Void param) {
            VISITED_METHOD.set("visitVariableType");
            return "variable";
        }

        @Override
        public String visitTypeVariable(TypeVariable typeVariable, Void param) {
            VISITED_METHOD.set("visitTypeVariable");
            return "typeVar";
        }

        @Override
        public String visitCapturedType(CapturedType capturedType, Void param) {
            VISITED_METHOD.set("visitCapturedType");
            return "captured";
        }

        @Override
        public String visitMethodType(ExecutableType methodType, Void param) {
            VISITED_METHOD.set("visitMethodType");
            return "method";
        }

        @Override
        public String visitIntersectionType(IntersectionType intersectionType, Void param) {
            VISITED_METHOD.set("visitIntersectionType");
            return "intersection";
        }

        @Override
        public String visitDeclaredType(DeclaredType declaredType, Void param) {
            VISITED_METHOD.set("visitDeclaredType");
            return "declared";
        }

        @Override
        public String visitWildcardType(WildcardType wildcardType, Void param) {
            VISITED_METHOD.set("visitWildcardType");
            return "wildcard";
        }

        @Override
        public String visitArrayType(ArrayType arrayType, Void param) {
            VISITED_METHOD.set("visitArrayType");
            return "array";
        }

        @Override
        public String visitPrimitiveType(PrimitiveType primitiveType, Void param) {
            VISITED_METHOD.set("visitPrimitiveType");
            return "primitive";
        }

        @Override
        public String visitPackageType(PackageType packageType, Void param) {
            VISITED_METHOD.set("visitPackageType");
            return "package";
        }

        @Override
        public String visitUnionType(UnionType unionType, Void param) {
            VISITED_METHOD.set("visitUnionType");
            return "union";
        }

        @Override
        public String visitModule(ModuleType moduleType, Void param) {
            VISITED_METHOD.set("visitModule");
            return "module";
        }
    };

    private static final CPrimitiveType INT_TYPE = new CPrimitiveType(TypeKind.INT);
    private static final CPrimitiveType BOOL_TYPE = new CPrimitiveType(TypeKind.BOOLEAN);

    private static CClassType createSimpleClassType(String name) {
        var classSymbol = new ClassSymbol(0, name, null);
        return new CClassType(null, classSymbol, List.of());
    }

    private static MethodSymbol createMethodSymbol(String methodName) {
        var classSymbol = new ClassSymbol(0, "TestClass", null);
        return new MethodSymbol(
                ElementKind.METHOD, 0, methodName, classSymbol,
                null, List.of(),
                INT_TYPE,
                List.of(), List.of(), List.of()
        );
    }

    private static CMethodType createSimpleMethodType(String methodName) {
        var methodSymbol = createMethodSymbol(methodName);
        return new CMethodType(
                methodSymbol, null, List.of(),
                INT_TYPE,
                List.of(), List.of()
        );
    }

    // ========================= CNullType =========================

    @Test
    void cNullType_kind() {
        assertEquals(TypeKind.NULL, new CNullType().getKind());
    }

    @Test
    void cNullType_className() {
        assertEquals("null", new CNullType().getClassName());
    }

    @Test
    void cNullType_accept() {
        VISITED_METHOD.set(null);
        var result = new CNullType().accept(TEST_VISITOR, null);
        assertEquals("visitNullType", VISITED_METHOD.get());
        assertEquals("null", result);
    }

    @Test
    void cNullType_equals() {
        var a = new CNullType();
        assertEquals(a, new CNullType());
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "not a type");
        assertNotEquals(a, new CNoType());
    }

    @Test
    void cNullType_hashCode() {
        assertEquals(32, new CNullType().hashCode());
        assertEquals(new CNullType().hashCode(), new CNullType().hashCode());
    }

    @Test
    void cNullType_toString() {
        assertNotNull(new CNullType().toString());
    }

    // ========================= CNoType =========================

    @Test
    void cNoType_kind() {
        assertEquals(TypeKind.NONE, new CNoType().getKind());
    }

    @Test
    void cNoType_className() {
        assertEquals("NO", new CNoType().getClassName());
    }

    @Test
    void cNoType_accept() {
        VISITED_METHOD.set(null);
        var result = new CNoType().accept(TEST_VISITOR, null);
        assertEquals("visitNoType", VISITED_METHOD.get());
        assertEquals("no", result);
    }

    @Test
    void cNoType_equals() {
        var a = new CNoType();
        assertEquals(a, new CNoType());
        assertNotEquals(a, null);
        assertNotEquals(a, "string");
        assertNotEquals(a, new CVoidType());
    }

    @Test
    void cNoType_hashCode() {
        assertEquals(32, new CNoType().hashCode());
    }

    @Test
    void cNoType_toString() {
        assertNotNull(new CNoType().toString());
    }

    // ========================= CUnknownType =========================

    @Test
    void cUnknownType_kind() {
        assertEquals(TypeKind.ERROR, new CUnknownType().getKind());
    }

    @Test
    void cUnknownType_className() {
        assertEquals("", new CUnknownType().getClassName());
    }

    @Test
    void cUnknownType_accept() {
        VISITED_METHOD.set(null);
        var result = new CUnknownType().accept(TEST_VISITOR, null);
        assertEquals("visitUnknownType", VISITED_METHOD.get());
        assertEquals("unknown", result);
    }

    @Test
    void cUnknownType_equals() {
        var a = new CUnknownType();
        var b = new CUnknownType();
        assertNotEquals(a, b);
        assertNotEquals(a, a);
    }

    @Test
    void cUnknownType_hashCode() {
        assertEquals(0, new CUnknownType().hashCode());
    }

    @Test
    void cUnknownType_toString() {
        assertNotNull(new CUnknownType().toString());
    }

    @Test
    void cUnknownType_enclosingType() {
        assertNull(new CUnknownType().getEnclosingType());
    }

    // ========================= CVoidType =========================

    @Test
    void cVoidType_kind() {
        assertEquals(TypeKind.VOID, new CVoidType().getKind());
    }

    @Test
    void cVoidType_className() {
        assertEquals("void", new CVoidType().getClassName());
    }

    @Test
    void cVoidType_accept() {
        VISITED_METHOD.set(null);
        var result = new CVoidType().accept(TEST_VISITOR, null);
        assertEquals("visitNoType", VISITED_METHOD.get());
        assertEquals("no", result);
    }

    @Test
    void cVoidType_equals() {
        var a = new CVoidType();
        assertEquals(a, new CVoidType());
        assertNotEquals(a, null);
        assertNotEquals(a, "string");
        assertNotEquals(a, new CNoType());
    }

    @Test
    void cVoidType_hashCode() {
        assertEquals(32, new CVoidType().hashCode());
    }

    @Test
    void cVoidType_toString() {
        assertEquals("void", new CVoidType().toString());
    }

    // ========================= CTypeVariable =========================

    @Test
    void cTypeVariable_kind() {
        assertEquals(TypeKind.TYPEVAR, new CTypeVariable("T").getKind());
    }

    @Test
    void cTypeVariable_accept() {
        VISITED_METHOD.set(null);
        var result = new CTypeVariable("T").accept(TEST_VISITOR, null);
        assertEquals("visitTypeVariable", VISITED_METHOD.get());
        assertEquals("typeVar", result);
    }

    @Test
    void cTypeVariable_isNotCaptured() {
        assertFalse(new CTypeVariable("T").isCaptured());
    }

    @Test
    void cTypeVariable_asElement() {
        var type = new CTypeVariable("T");
        assertNotNull(type.asElement());
        assertEquals("T", type.asElement().getSimpleName());
    }

    @Test
    void cTypeVariable_noBounds() {
        var type = new CTypeVariable("T");
        assertNull(type.getUpperBound());
        assertNull(type.getLowerBound());
    }

    @Test
    void cTypeVariable_withUpperBound() {
        var type = new CTypeVariable("T", null, INT_TYPE, null);
        assertEquals(INT_TYPE, type.getUpperBound());
        assertNull(type.getLowerBound());
    }

    @Test
    void cTypeVariable_withLowerBound() {
        var type = new CTypeVariable("T", null, null, INT_TYPE);
        assertNull(type.getUpperBound());
        assertEquals(INT_TYPE, type.getLowerBound());
    }

    @Test
    void cTypeVariable_setUpperBound() {
        var type = new CTypeVariable("T");
        assertNull(type.getUpperBound());
        type.setUpperBound(INT_TYPE);
        assertEquals(INT_TYPE, type.getUpperBound());
    }

    @Test
    void cTypeVariable_setLowerBound() {
        var type = new CTypeVariable("T");
        assertNull(type.getLowerBound());
        type.setLowerBound(INT_TYPE);
        assertEquals(INT_TYPE, type.getLowerBound());
    }

    @Test
    void cTypeVariable_className_noBounds() {
        assertEquals("T", new CTypeVariable("T").getClassName());
    }

    @Test
    void cTypeVariable_className_upperBound() {
        assertEquals("T extends int",
                new CTypeVariable("T", null, INT_TYPE, null).getClassName());
    }

    @Test
    void cTypeVariable_className_lowerBound() {
        assertEquals("T super int",
                new CTypeVariable("T", null, null, INT_TYPE).getClassName());
    }

    @Test
    void cTypeVariable_toString_noBounds() {
        assertEquals("T", new CTypeVariable("T").toString());
    }

    @Test
    void cTypeVariable_toString_upperBound() {
        assertEquals("T extends int",
                new CTypeVariable("T", null, INT_TYPE, null).toString());
    }

    @Test
    void cTypeVariable_toString_lowerBound() {
        assertEquals("T super int",
                new CTypeVariable("T", null, null, INT_TYPE).toString());
    }

    @Test
    void cTypeVariable_equals() {
        assertNotEquals(new CTypeVariable("T"), new CTypeVariable("T"));
    }

    @Test
    void cTypeVariable_hashCode() {
        assertEquals(32, new CTypeVariable("T").hashCode());
    }

    // ========================= CVariableType =========================

    @Test
    void cVariableType_withNullInterferedType() {
        var type = new CVariableType(null);
        assertNull(type.getInterferedType());
        assertNull(type.getKind());
        assertEquals("var", type.getClassName());
    }

    @Test
    void cVariableType_withInterferedType() {
        var type = new CVariableType(INT_TYPE);
        assertEquals(INT_TYPE, type.getInterferedType());
        assertEquals(TypeKind.INT, type.getKind());
        assertEquals("int", type.getClassName());
    }

    @Test
    void cVariableType_isPrimitiveType() {
        assertTrue(new CVariableType(INT_TYPE).isPrimitiveType());
        assertFalse(new CVariableType(null).isPrimitiveType());
    }

    @Test
    void cVariableType_accept() {
        VISITED_METHOD.set(null);
        var result = new CVariableType(null).accept(TEST_VISITOR, null);
        assertEquals("visitVariableType", VISITED_METHOD.get());
        assertEquals("variable", result);
    }

    @Test
    void cVariableType_toString() {
        assertEquals("var", new CVariableType(null).toString());
    }

    @Test
    void cVariableType_asTypeElement_null() {
        assertNull(new CVariableType(null).asTypeElement());
    }

    @Test
    void cVariableType_asElement_null() {
        assertNull(new CVariableType(null).asElement());
    }

    @Test
    void cVariableType_asTypeElement_delegates() {
        var classSymbol = new ClassSymbol(0, "Foo", null);
        var classType = new CClassType(null, classSymbol, List.of());
        var type = new CVariableType(classType);
        assertEquals(classType.asTypeElement(), type.asTypeElement());
    }

    @Test
    void cVariableType_asElement_delegates() {
        var classSymbol = new ClassSymbol(0, "Foo", null);
        var classType = new CClassType(null, classSymbol, List.of());
        var type = new CVariableType(classType);
        assertEquals(classType.asElement(), type.asElement());
    }

    @Test
    void cVariableType_equals_sameInterferedType() {
        var interfered = new CPrimitiveType(TypeKind.INT);
        assertEquals(new CVariableType(interfered), new CVariableType(interfered));
    }

    @Test
    void cVariableType_equals_differentInterferedType() {
        assertNotEquals(
                new CVariableType(new CPrimitiveType(TypeKind.INT)),
                new CVariableType(new CPrimitiveType(TypeKind.BOOLEAN)));
    }

    @Test
    void cVariableType_equals_bothNull() {
        assertEquals(new CVariableType(null), new CVariableType(null));
    }

    @Test
    void cVariableType_equals_notVariableType() {
        assertNotEquals(new CVariableType(null), "string");
    }

    @Test
    void cVariableType_hashCode() {
        var interfered = new CPrimitiveType(TypeKind.INT);
        assertEquals(
                new CVariableType(interfered).hashCode(),
                new CVariableType(interfered).hashCode());
    }

    // ========================= CCapturedType =========================

    @Test
    void cCapturedType_kind() {
        assertEquals(TypeKind.TYPEVAR,
                new CCapturedType("c", null, null, null, null).getKind());
    }

    @Test
    void cCapturedType_accept() {
        VISITED_METHOD.set(null);
        var result = new CCapturedType("c", null, null, null, null).accept(TEST_VISITOR, null);
        assertEquals("visitTypeVariable", VISITED_METHOD.get());
        assertEquals("typeVar", result);
    }

    @Test
    void cCapturedType_getWildcard() {
        assertNull(new CCapturedType("c", null, null, null, null).getWildcard());
    }

    @Test
    void cCapturedType_className() {
        assertEquals("captured", new CCapturedType("c", null, null, null, null).getClassName());
    }

    @Test
    void cCapturedType_isNotCaptured() {
        assertFalse(new CCapturedType("c", null, null, null, null).isCaptured());
    }

    @Test
    void cCapturedType_asElement() {
        var type = new CCapturedType("capture", null, null, null, null);
        assertNotNull(type.asElement());
        assertEquals("capture", type.asElement().getSimpleName());
    }

    @Test
    void cCapturedType_upperLowerBounds() {
        var type = new CCapturedType("c", null, INT_TYPE, BOOL_TYPE, null);
        assertEquals(INT_TYPE, type.getUpperBound());
        assertEquals(BOOL_TYPE, type.getLowerBound());
    }

    @Test
    void cCapturedType_equals() {
        assertEquals(
                new CCapturedType("c", null, null, null, null),
                new CCapturedType("c", null, null, null, null));
    }

    @Test
    void cCapturedType_hashCode() {
        assertEquals(
                new CCapturedType("c", null, null, null, null).hashCode(),
                new CCapturedType("c", null, null, null, null).hashCode());
    }

    @Test
    void cCapturedType_toString() {
        assertNotNull(new CCapturedType("c", null, null, null, null).toString());
    }

    // ========================= CMethodType =========================

    @Test
    void cMethodType_kind() {
        assertEquals(TypeKind.EXECUTABLE, createSimpleMethodType("m").getKind());
    }

    @Test
    void cMethodType_className() {
        assertEquals("methodType", createSimpleMethodType("m").getClassName());
    }

    @Test
    void cMethodType_accept() {
        VISITED_METHOD.set(null);
        var result = createSimpleMethodType("m").accept(TEST_VISITOR, null);
        assertEquals("visitMethodType", VISITED_METHOD.get());
        assertEquals("method", result);
    }

    @Test
    void cMethodType_returnType() {
        var type = createSimpleMethodType("m");
        assertNotNull(type.getReturnType());
        assertEquals(TypeKind.INT, type.getReturnType().getKind());
    }

    @Test
    void cMethodType_setReturnType() {
        var type = createSimpleMethodType("m");
        type.setReturnType(INT_TYPE);
        assertEquals(INT_TYPE, type.getReturnType());
    }

    @Test
    void cMethodType_parameterTypes_empty() {
        assertTrue(createSimpleMethodType("m").getParameterTypes().isEmpty());
    }

    @Test
    void cMethodType_addParameterType() {
        var type = createSimpleMethodType("m");
        type.addParameterType(INT_TYPE);
        assertEquals(1, type.getParameterTypes().size());
    }

    @Test
    void cMethodType_typeVariables_empty() {
        assertTrue(createSimpleMethodType("m").getTypeVariables().isEmpty());
    }

    @Test
    void cMethodType_addTypeVariable() {
        var type = createSimpleMethodType("m");
        type.addTypeVariable(new CTypeVariable("T"));
        assertEquals(1, type.getTypeVariables().size());
    }

    @Test
    void cMethodType_receiverType_null() {
        assertNull(createSimpleMethodType("m").getReceiverType());
    }

    @Test
    void cMethodType_receiverType() {
        var methodSymbol = createMethodSymbol("m");
        var type = new CMethodType(methodSymbol, INT_TYPE, List.of(),
                INT_TYPE, List.of(), List.of());
        assertEquals(INT_TYPE, type.getReceiverType());
    }

    @Test
    void cMethodType_thrownTypes_empty() {
        assertTrue(createSimpleMethodType("m").getThrownTypes().isEmpty());
    }

    @Test
    void cMethodType_setThrownTypes() {
        var type = createSimpleMethodType("m");
        type.setThrownTypes(List.of(INT_TYPE));
        assertEquals(1, type.getThrownTypes().size());
    }

    @Test
    void cMethodType_getMethodSymbol() {
        assertNotNull(createSimpleMethodType("m").getMethodSymbol());
    }

    @Test
    void cMethodType_getOwner() {
        var type = createSimpleMethodType("m");
        assertNotNull(type.getOwner());
    }

    @Test
    void cMethodType_toString() {
        assertNotNull(createSimpleMethodType("m").toString());
    }

    @Test
    void cMethodType_equals_sameSymbol() {
        var symbol = createMethodSymbol("m");
        var a = new CMethodType(symbol, null, List.of(),
                INT_TYPE, List.of(), List.of());
        var b = new CMethodType(symbol, null, List.of(),
                INT_TYPE, List.of(), List.of());
        assertEquals(a, b);
    }

    @Test
    void cMethodType_equals_differentSymbol() {
        var a = createSimpleMethodType("m1");
        var b = createSimpleMethodType("m2");
        assertNotEquals(a, b);
    }

    @Test
    void cMethodType_equals_notMethodType() {
        assertNotEquals(createSimpleMethodType("m"), "string");
    }

    @Test
    void cMethodType_hashCode() {
        var symbol = createMethodSymbol("m");
        var a = new CMethodType(symbol, null, List.of(),
                INT_TYPE, List.of(), List.of());
        var b = new CMethodType(symbol, null, List.of(),
                INT_TYPE, List.of(), List.of());
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void cMethodType_toString_containsReturnTypeAndParams() {
        var methodSymbol = createMethodSymbol("m");
        var type = new CMethodType(methodSymbol, null, List.of(),
                INT_TYPE, List.of(BOOL_TYPE, INT_TYPE), List.of());
        var str = type.toString();
        assertNotNull(str);
        assertTrue(str.contains("int"));
    }

    // ========================= CIntersectionType =========================

    @Test
    void cIntersectionType_kind() {
        assertEquals(TypeKind.INTERSECTION,
                new CIntersectionType(List.of(BOOL_TYPE, INT_TYPE)).getKind());
    }

    @Test
    void cIntersectionType_accept() {
        VISITED_METHOD.set(null);
        var result = new CIntersectionType(List.of(BOOL_TYPE, INT_TYPE)).accept(TEST_VISITOR, null);
        assertEquals("visitIntersectionType", VISITED_METHOD.get());
        assertEquals("intersection", result);
    }

    @Test
    void cIntersectionType_bounds() {
        var type = new CIntersectionType(List.of(BOOL_TYPE, INT_TYPE));
        assertEquals(2, type.getBounds().size());
    }

    @Test
    void cIntersectionType_className() {
        assertEquals("boolean | int",
                new CIntersectionType(List.of(BOOL_TYPE, INT_TYPE)).getClassName());
    }

    @Test
    void cIntersectionType_equals() {
        assertEquals(
                new CIntersectionType(List.of(BOOL_TYPE, INT_TYPE)),
                new CIntersectionType(List.of(BOOL_TYPE, INT_TYPE)));
    }

    @Test
    void cIntersectionType_equals_different() {
        assertNotEquals(
                new CIntersectionType(List.of(BOOL_TYPE)),
                new CIntersectionType(List.of(INT_TYPE)));
    }

    @Test
    void cIntersectionType_equals_notIntersection() {
        assertNotEquals(new CIntersectionType(List.of(BOOL_TYPE)), "string");
    }

    @Test
    void cIntersectionType_hashCode() {
        assertEquals(
                new CIntersectionType(List.of(BOOL_TYPE, INT_TYPE)).hashCode(),
                new CIntersectionType(List.of(BOOL_TYPE, INT_TYPE)).hashCode());
    }

    @Test
    void cIntersectionType_toString() {
        assertNotNull(new CIntersectionType(List.of(BOOL_TYPE, INT_TYPE)).toString());
    }

    // ========================= CErrorType =========================

    @Test
    void cErrorType_kind() {
        assertEquals(TypeKind.DECLARED, new CErrorType(new ErrorSymbol("e")).getKind());
    }

    @Test
    void cErrorType_accept() {
        VISITED_METHOD.set(null);
        var result = new CErrorType(new ErrorSymbol("e")).accept(TEST_VISITOR, null);
        assertEquals("visitDeclaredType", VISITED_METHOD.get());
        assertEquals("declared", result);
    }

    @Test
    void cErrorType_className() {
        assertNotNull(new CErrorType(new ErrorSymbol("e")).getClassName());
    }

    @Test
    void cErrorType_equals() {
        var a = new CErrorType(new ErrorSymbol("e1"));
        var b = new CErrorType(new ErrorSymbol("e2"));
        assertNotEquals(a, b);
        assertNotEquals(a, a);
    }

    @Test
    void cErrorType_hashCode() {
        assertEquals(0, new CErrorType(new ErrorSymbol("e")).hashCode());
    }

    @Test
    void cErrorType_toString() {
        assertNotNull(new CErrorType(new ErrorSymbol("e")).toString());
    }

    @Test
    void cErrorType_isError() {
        assertTrue(new CErrorType(new ErrorSymbol("error")).isError());
    }

    // ========================= CPackageType =========================

    @Test
    void cPackageType_kind() {
        assertEquals(TypeKind.PACKAGE,
                new CPackageType(new PackageSymbol(null, "mypkg")).getKind());
    }

    @Test
    void cPackageType_accept() {
        VISITED_METHOD.set(null);
        var result = new CPackageType(new PackageSymbol(null, "mypkg")).accept(TEST_VISITOR, null);
        assertEquals("visitPackageType", VISITED_METHOD.get());
        assertEquals("package", result);
    }

    @Test
    void cPackageType_className() {
        assertEquals("Package", new CPackageType(new PackageSymbol(null, "mypkg")).getClassName());
    }

    @Test
    void cPackageType_asElement() {
        var type = new CPackageType(new PackageSymbol(null, "mypkg"));
        assertNotNull(type.asElement());
        assertTrue(type.asElement() instanceof PackageSymbol);
    }

    @Test
    void cPackageType_equals_same() {
        assertEquals(
                new CPackageType(new PackageSymbol(null, "mypkg")),
                new CPackageType(new PackageSymbol(null, "mypkg")));
    }

    @Test
    void cPackageType_equals_different() {
        assertNotEquals(
                new CPackageType(new PackageSymbol(null, "pkg1")),
                new CPackageType(new PackageSymbol(null, "pkg2")));
    }

    @Test
    void cPackageType_equals_notPackage() {
        assertNotEquals(new CPackageType(new PackageSymbol(null, "p")), "string");
    }

    @Test
    void cPackageType_hashCode() {
        assertEquals(
                new CPackageType(new PackageSymbol(null, "mypkg")).hashCode(),
                new CPackageType(new PackageSymbol(null, "mypkg")).hashCode());
    }

    @Test
    void cPackageType_toString() {
        assertNotNull(new CPackageType(new PackageSymbol(null, "mypkg")).toString());
    }

    // ========================= CClassType =========================

    @Test
    void cClassType_kind() {
        assertEquals(TypeKind.DECLARED, createSimpleClassType("MyClass").getKind());
    }

    @Test
    void cClassType_accept() {
        VISITED_METHOD.set(null);
        var result = createSimpleClassType("MyClass").accept(TEST_VISITOR, null);
        assertEquals("visitDeclaredType", VISITED_METHOD.get());
        assertEquals("declared", result);
    }

    @Test
    void cClassType_className() {
        var type = createSimpleClassType("MyClass");
        assertNotNull(type.getClassName());
    }

    @Test
    void cClassType_asElement() {
        var type = createSimpleClassType("MyClass");
        assertNotNull(type.asElement());
        assertTrue(type.asElement() instanceof TypeSymbol);
    }

    @Test
    void cClassType_enclosingType_null() {
        assertNull(createSimpleClassType("MyClass").getEnclosingType());
    }

    @Test
    void cClassType_setEnclosingType() {
        var type = createSimpleClassType("MyClass");
        type.setOuterType(INT_TYPE);
        assertEquals(INT_TYPE, type.getEnclosingType());
    }

    @Test
    void cClassType_typeArguments_empty() {
        assertTrue(createSimpleClassType("MyClass").getTypeArguments().isEmpty());
    }

    @Test
    void cClassType_typeArguments_withArgs() {
        var classSymbol = new ClassSymbol(0, "MyClass", null);
        var type = new CClassType(null, classSymbol, List.of(INT_TYPE));
        assertEquals(1, type.getTypeArguments().size());
    }

    @Test
    void cClassType_setTypeArguments() {
        var type = createSimpleClassType("MyClass");
        type.setTypeArguments(List.of(INT_TYPE, BOOL_TYPE));
        assertEquals(2, type.getTypeArguments().size());
    }

    @Test
    void cClassType_setTypeArguments_null() {
        var type = createSimpleClassType("MyClass");
        type.setTypeArguments(null);
        assertTrue(type.getTypeArguments().isEmpty());
    }

    @Test
    void cClassType_addTypeArgument() {
        var type = createSimpleClassType("MyClass");
        type.addTypeArgument(INT_TYPE);
        assertEquals(1, type.getTypeArguments().size());
    }

    @Test
    void cClassType_toString() {
        assertNotNull(createSimpleClassType("MyClass").toString());
    }

    @Test
    void cClassType_getSupertypeField() {
        assertNull(createSimpleClassType("MyClass").getSupertypeField());
    }

    @Test
    void cClassType_setSupertypeField() {
        var type = createSimpleClassType("MyClass");
        var superType = createSimpleClassType("SuperClass");
        type.setSupertypeField(superType);
        assertEquals(superType, type.getSupertypeField());
    }

    @Test
    void cClassType_setSupertypeField_self_throws() {
        var type = createSimpleClassType("MyClass");
        assertThrows(IllegalStateException.class, () -> type.setSupertypeField(type));
    }

    @Test
    void cClassType_getInterfacesField() {
        assertNull(createSimpleClassType("MyClass").getInterfacesField());
    }

    @Test
    void cClassType_isParameterized() {
        assertFalse(createSimpleClassType("MyClass").isParameterized());
    }

    @Test
    void cClassType_getAllParameters() {
        assertNotNull(createSimpleClassType("MyClass").getAllParameters());
    }

    @Test
    void cClassType_equals_sameType() {
        assertEquals(createSimpleClassType("MyClass"), createSimpleClassType("MyClass"));
    }

    @Test
    void cClassType_equals_differentOuterType() {
        var classSymbol = new ClassSymbol(0, "MyClass", null);
        var a = new CClassType(null, classSymbol, List.of());
        var b = new CClassType(INT_TYPE, classSymbol, List.of());
        assertNotEquals(a, b);
    }

    @Test
    void cClassType_equals_notClassType() {
        assertNotEquals(createSimpleClassType("MyClass"), "string");
    }

    @Test
    void cClassType_hashCode() {
        assertEquals(
                createSimpleClassType("MyClass").hashCode(),
                createSimpleClassType("MyClass").hashCode());
    }

    // ========================= CWildcardType =========================

    @Test
    void cWildcardType_extendsBound() {
        var type = new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null);
        assertEquals(INT_TYPE, type.getExtendsBound());
        assertNull(type.getSuperBound());
        assertEquals(INT_TYPE, type.getBound());
        assertEquals(BoundKind.EXTENDS, type.getBoundKind());
    }

    @Test
    void cWildcardType_superBound() {
        var type = new CWildcardType(INT_TYPE, BoundKind.SUPER, null);
        assertNull(type.getExtendsBound());
        assertEquals(INT_TYPE, type.getSuperBound());
        assertEquals(INT_TYPE, type.getBound());
        assertEquals(BoundKind.SUPER, type.getBoundKind());
    }

    @Test
    void cWildcardType_unbound() {
        var type = new CWildcardType(null, BoundKind.UNBOUND, null);
        assertNull(type.getExtendsBound());
        assertNull(type.getSuperBound());
        assertNull(type.getBound());
        assertEquals(BoundKind.UNBOUND, type.getBoundKind());
    }

    @Test
    void cWildcardType_kind() {
        assertEquals(TypeKind.WILDCARD,
                new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null).getKind());
    }

    @Test
    void cWildcardType_accept() {
        VISITED_METHOD.set(null);
        var result = new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null).accept(TEST_VISITOR, null);
        assertEquals("visitWildcardType", VISITED_METHOD.get());
        assertEquals("wildcard", result);
    }

    @Test
    void cWildcardType_className_extends() {
        assertEquals("? extends int",
                new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null).getClassName());
    }

    @Test
    void cWildcardType_className_super() {
        assertEquals("? super int",
                new CWildcardType(INT_TYPE, BoundKind.SUPER, null).getClassName());
    }

    @Test
    void cWildcardType_className_unbound() {
        assertEquals("?",
                new CWildcardType(null, BoundKind.UNBOUND, null).getClassName());
    }

    @Test
    void cWildcardType_equals_sameType() {
        assertEquals(
                new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null),
                new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null));
    }

    @Test
    void cWildcardType_equals_differentKind() {
        assertNotEquals(
                new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null),
                new CWildcardType(INT_TYPE, BoundKind.SUPER, null));
    }

    @Test
    void cWildcardType_equals_notWildcard() {
        assertNotEquals(
                new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null), "string");
    }

    @Test
    void cWildcardType_hashCode() {
        assertEquals(
                new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null).hashCode(),
                new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null).hashCode());
    }

    @Test
    void cWildcardType_toString() {
        assertNotNull(new CWildcardType(INT_TYPE, BoundKind.EXTENDS, null).toString());
    }

    @Test
    void cWildcardType_constructor_withBound() {
        var classSymbol = new ClassSymbol(0, "T", null);
        var typeVar = new CTypeVariable("T");
        var type = new CWildcardType(INT_TYPE, BoundKind.EXTENDS, classSymbol, typeVar);
        assertEquals(BoundKind.EXTENDS, type.getBoundKind());
    }

    // ========================= CArrayType =========================

    @Test
    void cArrayType_kind() {
        assertEquals(TypeKind.ARRAY, new CArrayType(INT_TYPE).getKind());
    }

    @Test
    void cArrayType_accept() {
        VISITED_METHOD.set(null);
        var result = new CArrayType(INT_TYPE).accept(TEST_VISITOR, null);
        assertEquals("visitArrayType", VISITED_METHOD.get());
        assertEquals("array", result);
    }

    @Test
    void cArrayType_componentType() {
        assertEquals(INT_TYPE, new CArrayType(INT_TYPE).getComponentType());
    }

    @Test
    void cArrayType_className() {
        assertEquals("[int", new CArrayType(INT_TYPE).getClassName());
    }

    @Test
    void cArrayType_isVarArgs_false() {
        assertFalse(new CArrayType(INT_TYPE).isVarArgs());
    }

    @Test
    void cArrayType_makeVarArg() {
        assertTrue(new CArrayType(INT_TYPE).makeVarArg().isVarArgs());
    }

    @Test
    void cArrayType_equals_sameComponent() {
        assertEquals(new CArrayType(INT_TYPE), new CArrayType(INT_TYPE));
    }

    @Test
    void cArrayType_equals_differentComponent() {
        assertNotEquals(new CArrayType(INT_TYPE), new CArrayType(BOOL_TYPE));
    }

    @Test
    void cArrayType_equals_notArray() {
        assertNotEquals(new CArrayType(INT_TYPE), "string");
    }

    @Test
    void cArrayType_hashCode() {
        assertEquals(
                new CArrayType(INT_TYPE).hashCode(),
                new CArrayType(INT_TYPE).hashCode());
    }

    @Test
    void cArrayType_toString() {
        assertNotNull(new CArrayType(INT_TYPE).toString());
    }

    // ========================= CPrimitiveType =========================

    @Test
    void cPrimitiveType_kind() {
        assertEquals(TypeKind.INT, new CPrimitiveType(TypeKind.INT).getKind());
        assertEquals(TypeKind.BOOLEAN, new CPrimitiveType(TypeKind.BOOLEAN).getKind());
        assertEquals(TypeKind.CHAR, new CPrimitiveType(TypeKind.CHAR).getKind());
        assertEquals(TypeKind.BYTE, new CPrimitiveType(TypeKind.BYTE).getKind());
        assertEquals(TypeKind.SHORT, new CPrimitiveType(TypeKind.SHORT).getKind());
        assertEquals(TypeKind.FLOAT, new CPrimitiveType(TypeKind.FLOAT).getKind());
        assertEquals(TypeKind.LONG, new CPrimitiveType(TypeKind.LONG).getKind());
        assertEquals(TypeKind.DOUBLE, new CPrimitiveType(TypeKind.DOUBLE).getKind());
    }

    @Test
    void cPrimitiveType_invalidKind_throws() {
        assertThrows(IllegalArgumentException.class, () -> new CPrimitiveType(null));
        assertThrows(IllegalArgumentException.class, () -> new CPrimitiveType(TypeKind.DECLARED));
        assertThrows(IllegalArgumentException.class, () -> new CPrimitiveType(TypeKind.VOID));
    }

    @Test
    void cPrimitiveType_accept() {
        VISITED_METHOD.set(null);
        var result = new CPrimitiveType(TypeKind.INT).accept(TEST_VISITOR, null);
        assertEquals("visitPrimitiveType", VISITED_METHOD.get());
        assertEquals("primitive", result);
    }

    @Test
    void cPrimitiveType_className() {
        assertEquals("boolean", new CPrimitiveType(TypeKind.BOOLEAN).getClassName());
        assertEquals("char", new CPrimitiveType(TypeKind.CHAR).getClassName());
        assertEquals("byte", new CPrimitiveType(TypeKind.BYTE).getClassName());
        assertEquals("short", new CPrimitiveType(TypeKind.SHORT).getClassName());
        assertEquals("int", new CPrimitiveType(TypeKind.INT).getClassName());
        assertEquals("float", new CPrimitiveType(TypeKind.FLOAT).getClassName());
        assertEquals("long", new CPrimitiveType(TypeKind.LONG).getClassName());
        assertEquals("double", new CPrimitiveType(TypeKind.DOUBLE).getClassName());
    }

    @Test
    void cPrimitiveType_isPrimitiveType() {
        assertTrue(new CPrimitiveType(TypeKind.INT).isPrimitiveType());
    }

    @Test
    void cPrimitiveType_toString() {
        assertEquals("int", new CPrimitiveType(TypeKind.INT).toString());
        assertEquals("boolean", new CPrimitiveType(TypeKind.BOOLEAN).toString());
    }

    @Test
    void cPrimitiveType_equals_same() {
        assertEquals(new CPrimitiveType(TypeKind.INT), new CPrimitiveType(TypeKind.INT));
    }

    @Test
    void cPrimitiveType_equals_different() {
        assertNotEquals(new CPrimitiveType(TypeKind.INT), new CPrimitiveType(TypeKind.BOOLEAN));
    }

    @Test
    void cPrimitiveType_equals_notPrimitive() {
        assertNotEquals(new CPrimitiveType(TypeKind.INT), "string");
    }

    @Test
    void cPrimitiveType_hashCode() {
        assertEquals(
                new CPrimitiveType(TypeKind.INT).hashCode(),
                new CPrimitiveType(TypeKind.INT).hashCode());
    }

    // ========================= CUnionType =========================

    @Test
    void cUnionType_kind() {
        assertEquals(TypeKind.UNION,
                new CUnionType(null, List.of(INT_TYPE, BOOL_TYPE)).getKind());
    }

    @Test
    void cUnionType_accept() {
        VISITED_METHOD.set(null);
        var result = new CUnionType(null, List.of(INT_TYPE, BOOL_TYPE)).accept(TEST_VISITOR, null);
        assertEquals("visitUnionType", VISITED_METHOD.get());
        assertEquals("union", result);
    }

    @Test
    void cUnionType_alternatives() {
        assertEquals(2,
                new CUnionType(null, List.of(INT_TYPE, BOOL_TYPE)).getAlternatives().size());
    }

    @Test
    void cUnionType_className() {
        assertEquals("int & boolean",
                new CUnionType(null, List.of(INT_TYPE, BOOL_TYPE)).getClassName());
    }

    @Test
    void cUnionType_equals_same() {
        assertEquals(
                new CUnionType(null, List.of(INT_TYPE, BOOL_TYPE)),
                new CUnionType(null, List.of(INT_TYPE, BOOL_TYPE)));
    }

    @Test
    void cUnionType_equals_different() {
        assertNotEquals(
                new CUnionType(null, List.of(INT_TYPE)),
                new CUnionType(null, List.of(BOOL_TYPE)));
    }

    @Test
    void cUnionType_equals_notUnion() {
        assertNotEquals(new CUnionType(null, List.of(INT_TYPE)), "string");
    }

    @Test
    void cUnionType_hashCode() {
        assertEquals(
                new CUnionType(null, List.of(INT_TYPE, BOOL_TYPE)).hashCode(),
                new CUnionType(null, List.of(INT_TYPE, BOOL_TYPE)).hashCode());
    }

    @Test
    void cUnionType_toString() {
        assertNotNull(new CUnionType(null, List.of(INT_TYPE, BOOL_TYPE)).toString());
    }

    // ========================= UndetVarType =========================

    @Test
    void undetVarType_kind() {
        assertEquals(TypeKind.NONE, new UndetVarType(INT_TYPE).getKind());
    }

    @Test
    void undetVarType_accept_delegates() {
        VISITED_METHOD.set(null);
        var result = new UndetVarType(INT_TYPE).accept(TEST_VISITOR, null);
        assertEquals("visitPrimitiveType", VISITED_METHOD.get());
        assertEquals("primitive", result);
    }

    @Test
    void undetVarType_getClassName() {
        assertEquals("", new UndetVarType(INT_TYPE).getClassName());
    }

    @Test
    void undetVarType_getParameterTypes() {
        assertNotNull(new UndetVarType(INT_TYPE).getParameterTypes());
    }

    @Test
    void undetVarType_equals() {
        var a = new UndetVarType(INT_TYPE);
        assertNotEquals(a, a);
        assertNotEquals(a, new UndetVarType(INT_TYPE));
    }

    @Test
    void undetVarType_hashCode() {
        assertEquals(0, new UndetVarType(INT_TYPE).hashCode());
    }

    @Test
    void undetVarType_toString() {
        assertNotNull(new UndetVarType(INT_TYPE).toString());
    }

    // ========================= ModuleTypeImpl =========================

    @Test
    void moduleTypeImpl_kind() {
        assertEquals(TypeKind.MODULE,
                new ModuleTypeImpl(new ModuleSymbol(0, "m")).getKind());
    }

    @Test
    void moduleTypeImpl_accept() {
        VISITED_METHOD.set(null);
        var result = new ModuleTypeImpl(new ModuleSymbol(0, "m")).accept(TEST_VISITOR, null);
        assertEquals("visitModule", VISITED_METHOD.get());
        assertEquals("module", result);
    }

    @Test
    void moduleTypeImpl_className() {
        assertEquals("", new ModuleTypeImpl(new ModuleSymbol(0, "m")).getClassName());
    }

    @Test
    void moduleTypeImpl_asElement() {
        var type = new ModuleTypeImpl(new ModuleSymbol(0, "mymod"));
        assertNotNull(type.asElement());
        assertTrue(type.asElement() instanceof ModuleSymbol);
    }

    @Test
    void moduleTypeImpl_equals_same() {
        assertEquals(
                new ModuleTypeImpl(new ModuleSymbol(0, "m")),
                new ModuleTypeImpl(new ModuleSymbol(0, "m")));
    }

    @Test
    void moduleTypeImpl_equals_different() {
        assertNotEquals(
                new ModuleTypeImpl(new ModuleSymbol(0, "mod1")),
                new ModuleTypeImpl(new ModuleSymbol(0, "mod2")));
    }

    @Test
    void moduleTypeImpl_equals_notModule() {
        assertNotEquals(new ModuleTypeImpl(new ModuleSymbol(0, "m")), "string");
    }

    @Test
    void moduleTypeImpl_hashCode() {
        assertEquals(
                new ModuleTypeImpl(new ModuleSymbol(0, "m")).hashCode(),
                new ModuleTypeImpl(new ModuleSymbol(0, "m")).hashCode());
    }

    @Test
    void moduleTypeImpl_toString() {
        assertNotNull(new ModuleTypeImpl(new ModuleSymbol(0, "m")).toString());
    }

    // ========================= AbstractType =========================

    @Test
    void abstractType_getTypeArguments_default() {
        assertTrue(new CNullType().getTypeArguments().isEmpty());
    }

    @Test
    void abstractType_asElement() {
        assertNull(new CNullType().asElement());
    }

    @Test
    void abstractType_isError_errorType() {
        assertTrue(new CErrorType(new ErrorSymbol("error")).isError());
    }

    @Test
    void abstractType_setElement() {
        var type = new CNullType();
        type.setElement(null);
        assertNull(type.asElement());
    }

    // ========================= Cross-type equals =========================

    @Test
    void crossType_nullType_notEqualTo_noType() {
        assertNotEquals(new CNullType(), new CNoType());
    }

    @Test
    void crossType_nullType_notEqualTo_voidType() {
        assertNotEquals(new CNullType(), new CVoidType());
    }

    @Test
    void crossType_arrayType_notEqualTo_classType() {
        assertNotEquals(new CArrayType(INT_TYPE), createSimpleClassType("Foo"));
    }

    @Test
    void crossType_primitiveType_notEqualTo_variableType() {
        assertNotEquals(INT_TYPE, new CVariableType(INT_TYPE));
    }
}

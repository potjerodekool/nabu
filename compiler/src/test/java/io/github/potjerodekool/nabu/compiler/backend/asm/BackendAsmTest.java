package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.backend.asm.JvmSignatureBuilder;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.type.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackendAsmTest {

    // ---------------------------------------------------------------
    // JvmSignatureBuilder.appendTypeMirror – primitive types
    // ---------------------------------------------------------------

    @Test
    void appendTypeMirror_null_returnsObject() {
        final var sb = new StringBuilder();
        JvmSignatureBuilder.appendTypeMirror(sb, (TypeMirror) null);
        assertEquals("Ljava/lang/Object;", sb.toString());
    }

    @Test
    void appendTypeMirror_byte() {
        assertEquals("B", append(newPrimitive(TypeKind.BYTE)));
    }

    @Test
    void appendTypeMirror_short() {
        assertEquals("S", append(newPrimitive(TypeKind.SHORT)));
    }

    @Test
    void appendTypeMirror_char() {
        assertEquals("C", append(newPrimitive(TypeKind.CHAR)));
    }

    @Test
    void appendTypeMirror_int() {
        assertEquals("I", append(newPrimitive(TypeKind.INT)));
    }

    @Test
    void appendTypeMirror_long() {
        assertEquals("J", append(newPrimitive(TypeKind.LONG)));
    }

    @Test
    void appendTypeMirror_float() {
        assertEquals("F", append(newPrimitive(TypeKind.FLOAT)));
    }

    @Test
    void appendTypeMirror_double() {
        assertEquals("D", append(newPrimitive(TypeKind.DOUBLE)));
    }

    @Test
    void appendTypeMirror_boolean() {
        assertEquals("Z", append(newPrimitive(TypeKind.BOOLEAN)));
    }

    @Test
    void appendTypeMirror_void() {
        assertEquals("V", append(newPrimitive(TypeKind.VOID)));
    }

    // ---------------------------------------------------------------
    // JvmSignatureBuilder.appendTypeMirror – array type
    // ---------------------------------------------------------------

    @Test
    void appendTypeMirror_arrayOfInt() {
        final var intType = newPrimitive(TypeKind.INT);
        final var arrayType = mock(ArrayType.class);
        when(arrayType.getKind()).thenReturn(TypeKind.ARRAY);
        when(arrayType.getComponentType()).thenReturn(intType);
        assertEquals("[I", append(arrayType));
    }

    @Test
    void appendTypeMirror_arrayOfObject() {
        final var objectType = newDeclared("java.lang.String");
        final var arrayType = mock(ArrayType.class);
        when(arrayType.getKind()).thenReturn(TypeKind.ARRAY);
        when(arrayType.getComponentType()).thenReturn(objectType);
        assertEquals("[Ljava/lang/String;", append(arrayType));
    }

    @Test
    void appendTypeMirror_multiDimensionalArray() {
        final var intType = newPrimitive(TypeKind.INT);
        final var innerArray = mock(ArrayType.class);
        when(innerArray.getKind()).thenReturn(TypeKind.ARRAY);
        when(innerArray.getComponentType()).thenReturn(intType);
        final var outerArray = mock(ArrayType.class);
        when(outerArray.getKind()).thenReturn(TypeKind.ARRAY);
        when(outerArray.getComponentType()).thenReturn(innerArray);
        assertEquals("[[I", append(outerArray));
    }

    // ---------------------------------------------------------------
    // JvmSignatureBuilder.appendTypeMirror – type variable
    // ---------------------------------------------------------------

    @Test
    void appendTypeMirror_typeVariable() {
        final var tv = mock(TypeVariable.class);
        final var tpe = mock(TypeParameterElement.class);
        when(tv.getKind()).thenReturn(TypeKind.TYPEVAR);
        when(tv.asElement()).thenReturn(tpe);
        when(tpe.getSimpleName()).thenReturn("T");
        assertEquals("TT;", append(tv));
    }

    @Test
    void appendTypeMirror_typeVariable_elementNotTypeParameter() {
        final var tv = mock(TypeVariable.class);
        final var element = mock(Element.class);
        when(tv.getKind()).thenReturn(TypeKind.TYPEVAR);
        when(tv.asElement()).thenReturn(element);
        when(element.getSimpleName()).thenReturn("E");
        assertEquals("TE;", append(tv));
    }

    @Test
    void appendTypeMirror_typeVariable_notTypeVariableInstance() {
        final var tm = mock(TypeMirror.class);
        when(tm.getKind()).thenReturn(TypeKind.TYPEVAR);
        final var result = append(tm);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // ---------------------------------------------------------------
    // JvmSignatureBuilder.appendTypeMirror – wildcard type
    // ---------------------------------------------------------------

    @Test
    void appendTypeMirror_wildcardExtends() {
        final var upper = newDeclared("java.lang.Number");
        final var wt = mock(WildcardType.class);
        when(wt.getKind()).thenReturn(TypeKind.WILDCARD);
        when(wt.getUpperBound()).thenReturn(upper);
        when(wt.getLowerBound()).thenReturn(null);
        assertEquals("+Ljava/lang/Number;", append(wt));
    }

    @Test
    void appendTypeMirror_wildcardSuper() {
        final var lower = newDeclared("java.lang.Integer");
        final var wt = mock(WildcardType.class);
        when(wt.getKind()).thenReturn(TypeKind.WILDCARD);
        when(wt.getUpperBound()).thenReturn(null);
        when(wt.getLowerBound()).thenReturn(lower);
        assertEquals("-Ljava/lang/Integer;", append(wt));
    }

    @Test
    void appendTypeMirror_wildcardUnbound() {
        final var noneType = mock(TypeMirror.class);
        when(noneType.getKind()).thenReturn(TypeKind.NONE);
        final var wt = mock(WildcardType.class);
        when(wt.getKind()).thenReturn(TypeKind.WILDCARD);
        when(wt.getUpperBound()).thenReturn(noneType);
        when(wt.getLowerBound()).thenReturn(noneType);
        assertEquals("*", append(wt));
    }

    @Test
    void appendTypeMirror_wildcardExtendsNullBounds() {
        final var wt = mock(WildcardType.class);
        when(wt.getKind()).thenReturn(TypeKind.WILDCARD);
        when(wt.getUpperBound()).thenReturn(null);
        when(wt.getLowerBound()).thenReturn(null);
        assertEquals("*", append(wt));
    }

    // ---------------------------------------------------------------
    // JvmSignatureBuilder.appendTypeMirror – declared type
    // ---------------------------------------------------------------

    @Test
    void appendTypeMirror_declaredNoTypeArgs() {
        final var dt = newDeclared("java.lang.String");
        assertEquals("Ljava/lang/String;", append(dt));
    }

    @Test
    void appendTypeMirror_declaredWithTypeArgs() {
        final var innerType = newDeclared("java.lang.String");
        final var dt = mock(DeclaredType.class);
        when(dt.getKind()).thenReturn(TypeKind.DECLARED);
        final var te = mock(TypeElement.class);
        when(dt.asElement()).thenReturn(te);
        when(te.getQualifiedName()).thenReturn("java.util.List");
        doReturn(List.of(innerType)).when(dt).getTypeArguments();
        assertEquals("Ljava/util/List<Ljava/lang/String;>;", append(dt));
    }

    @Test
    void appendTypeMirror_declaredMultipleTypeArgs() {
        final var keyType = newDeclared("java.lang.String");
        final var valType = newDeclared("java.lang.Integer");
        final var dt = mock(DeclaredType.class);
        when(dt.getKind()).thenReturn(TypeKind.DECLARED);
        final var te = mock(TypeElement.class);
        when(dt.asElement()).thenReturn(te);
        when(te.getQualifiedName()).thenReturn("java.util.Map");
        doReturn(List.of(keyType, valType)).when(dt).getTypeArguments();
        assertEquals("Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;", append(dt));
    }

    @Test
    void appendTypeMirror_declaredElementNotTypeElement() {
        final var dt = mock(DeclaredType.class);
        when(dt.getKind()).thenReturn(TypeKind.DECLARED);
        final var element = mock(Element.class);
        when(dt.asElement()).thenReturn(element);
        assertEquals("Ljava/lang/Object;", append(dt));
    }

    @Test
    void appendTypeMirror_declaredNotDeclaredTypeInstance() {
        final var tm = mock(TypeMirror.class);
        when(tm.getKind()).thenReturn(TypeKind.DECLARED);
        assertEquals("Ljava/lang/Object;", append(tm));
    }

    // ---------------------------------------------------------------
    // JvmSignatureBuilder.appendTypeMirror – default
    // ---------------------------------------------------------------

    @Test
    void appendTypeMirror_defaultKind() {
        final var tm = mock(TypeMirror.class);
        when(tm.getKind()).thenReturn(TypeKind.NULL);
        assertEquals("Ljava/lang/Object;", append(tm));
    }

    // ---------------------------------------------------------------
    // JvmSignatureBuilder.buildFieldSignature
    // ---------------------------------------------------------------

    @Test
    void buildFieldSignature_null() {
        assertNull(JvmSignatureBuilder.buildFieldSignature(null));
    }

    @Test
    void buildFieldSignature_primitive() {
        assertNull(JvmSignatureBuilder.buildFieldSignature(newPrimitive(TypeKind.INT)));
    }

    @Test
    void buildFieldSignature_objectType() {
        assertNull(JvmSignatureBuilder.buildFieldSignature(newDeclared("java.lang.Object")));
    }

    @Test
    void buildFieldSignature_stringType() {
        final var stringType = newDeclared("java.lang.String");
        assertEquals("Ljava/lang/String;",
                JvmSignatureBuilder.buildFieldSignature(stringType));
    }

    @Test
    void buildFieldSignature_parameterizedType() {
        final var innerType = newDeclared("java.lang.String");
        final var dt = mock(DeclaredType.class);
        when(dt.getKind()).thenReturn(TypeKind.DECLARED);
        final var te = mock(TypeElement.class);
        when(dt.asElement()).thenReturn(te);
        when(te.getQualifiedName()).thenReturn("java.util.List");
        doReturn(List.of(innerType)).when(dt).getTypeArguments();
        assertEquals("Ljava/util/List<Ljava/lang/String;>;",
                JvmSignatureBuilder.buildFieldSignature(dt));
    }

    @Test
    void buildFieldSignature_arrayOfPrimitive() {
        final var byteType = newPrimitive(TypeKind.BYTE);
        final var arrayType = mock(ArrayType.class);
        when(arrayType.getKind()).thenReturn(TypeKind.ARRAY);
        when(arrayType.getComponentType()).thenReturn(byteType);
        assertEquals("[B", JvmSignatureBuilder.buildFieldSignature(arrayType));
    }

    @Test
    void buildFieldSignature_typeVariable() {
        final var tv = mock(TypeVariable.class);
        final var tpe = mock(TypeParameterElement.class);
        when(tv.getKind()).thenReturn(TypeKind.TYPEVAR);
        when(tv.asElement()).thenReturn(tpe);
        when(tpe.getSimpleName()).thenReturn("T");
        assertEquals("TT;", JvmSignatureBuilder.buildFieldSignature(tv));
    }

    // ---------------------------------------------------------------
    // JvmSignatureBuilder.buildClassSignature
    // ---------------------------------------------------------------

    @Test
    void buildClassSignature_noTypeParamsExtendsObjectNoInterfaces() {
        final var te = mock(TypeElement.class);
        when(te.getTypeParameters()).thenReturn(List.of());
        final var superType = newDeclared("java.lang.Object");
        when(te.getSuperclass()).thenReturn(superType);
        when(te.getInterfaces()).thenReturn(List.of());
        assertNull(JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_extendsNonObjectNoTypeParams() {
        final var te = mock(TypeElement.class);
        when(te.getTypeParameters()).thenReturn(List.of());
        final var superType = newDeclared("java.io.Serializable");
        when(te.getSuperclass()).thenReturn(superType);
        when(te.getInterfaces()).thenReturn(List.of());
        assertEquals("Ljava/io/Serializable;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_withSingleTypeParam() {
        final var tpe = mockTypeParameter("T", List.of());
        final var te = mock(TypeElement.class);
        doReturn(List.of(tpe)).when(te).getTypeParameters();
        final var superType = newDeclared("java.lang.Object");
        when(te.getSuperclass()).thenReturn(superType);
        when(te.getInterfaces()).thenReturn(List.of());
        assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Object;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_withBoundedTypeParam() {
        final var bound = newDeclared("java.lang.Number");
        final var tpe = mockTypeParameter("T", List.of(bound));
        final var te = mock(TypeElement.class);
        doReturn(List.of(tpe)).when(te).getTypeParameters();
        final var superType = newDeclared("java.lang.Object");
        when(te.getSuperclass()).thenReturn(superType);
        when(te.getInterfaces()).thenReturn(List.of());
        assertEquals("<T:Ljava/lang/Number;>Ljava/lang/Object;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_withMultipleBounds() {
        final var bound1 = newDeclared("java.lang.Comparable");
        final var bound2 = newDeclared("java.io.Serializable");
        final var tpe = mockTypeParameter("T", List.of(bound1, bound2));
        final var te = mock(TypeElement.class);
        doReturn(List.of(tpe)).when(te).getTypeParameters();
        final var superType = newDeclared("java.lang.Object");
        when(te.getSuperclass()).thenReturn(superType);
        when(te.getInterfaces()).thenReturn(List.of());
        assertEquals("<T:Ljava/lang/Comparable;:Ljava/io/Serializable;>Ljava/lang/Object;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_withMultipleTypeParams() {
        final var tpe1 = mockTypeParameter("K", List.of());
        final var tpe2 = mockTypeParameter("V", List.of());
        final var te = mock(TypeElement.class);
        doReturn(List.of(tpe1, tpe2)).when(te).getTypeParameters();
        final var superType = newDeclared("java.lang.Object");
        when(te.getSuperclass()).thenReturn(superType);
        when(te.getInterfaces()).thenReturn(List.of());
        assertEquals("<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/lang/Object;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_withInterface() {
        final var te = mock(TypeElement.class);
        when(te.getTypeParameters()).thenReturn(List.of());
        final var superType = newDeclared("java.lang.Object");
        when(te.getSuperclass()).thenReturn(superType);
        final var serializableType = newDeclared("java.io.Serializable");
        doReturn(List.of(serializableType)).when(te).getInterfaces();
        assertEquals("Ljava/lang/Object;Ljava/io/Serializable;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_withTypeParamsAndInterface() {
        final var tpe = mockTypeParameter("T", List.of());
        final var te = mock(TypeElement.class);
        doReturn(List.of(tpe)).when(te).getTypeParameters();
        final var superType = newDeclared("java.lang.Object");
        when(te.getSuperclass()).thenReturn(superType);
        final var serializableType = newDeclared("java.io.Serializable");
        doReturn(List.of(serializableType)).when(te).getInterfaces();
        assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/io/Serializable;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_typeParamWithPrimitiveBound() {
        final var bound = newPrimitive(TypeKind.INT);
        final var tpe = mockTypeParameter("T", List.of(bound));
        final var te = mock(TypeElement.class);
        doReturn(List.of(tpe)).when(te).getTypeParameters();
        final var superType = newDeclared("java.lang.Object");
        when(te.getSuperclass()).thenReturn(superType);
        when(te.getInterfaces()).thenReturn(List.of());
        assertEquals("<T:I>Ljava/lang/Object;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_superclassNotObjectNoInterfaces() {
        final var te = mock(TypeElement.class);
        when(te.getTypeParameters()).thenReturn(List.of());
        final var superType = newDeclared("java.util.AbstractList");
        when(te.getSuperclass()).thenReturn(superType);
        when(te.getInterfaces()).thenReturn(List.of());
        assertEquals("Ljava/util/AbstractList;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_multipleInterfaces() {
        final var te = mock(TypeElement.class);
        when(te.getTypeParameters()).thenReturn(List.of());
        final var superType = newDeclared("java.lang.Object");
        when(te.getSuperclass()).thenReturn(superType);
        final var serializableType = newDeclared("java.io.Serializable");
        final var comparableType = newDeclared("java.lang.Comparable");
        doReturn(List.of(serializableType, comparableType)).when(te).getInterfaces();
        assertEquals("Ljava/lang/Object;Ljava/io/Serializable;Ljava/lang/Comparable;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    @Test
    void buildClassSignature_superclassNull() {
        final var te = mock(TypeElement.class);
        when(te.getTypeParameters()).thenReturn(List.of());
        when(te.getSuperclass()).thenReturn(null);
        when(te.getInterfaces()).thenReturn(List.of());
        assertEquals("Ljava/lang/Object;",
                JvmSignatureBuilder.buildClassSignature(te));
    }

    // ---------------------------------------------------------------
    // JvmSignatureBuilder.buildMethodSignature
    // ---------------------------------------------------------------

    @Test
    void buildMethodSignature_noTypeParams() {
        final var stringType = newDeclared("java.lang.String");
        final var intType = newPrimitive(TypeKind.INT);
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{},
                stringType,
                List.of(intType)
        );
        assertEquals("(I)Ljava/lang/String;", result);
    }

    @Test
    void buildMethodSignature_emptyParamsAndVoidReturn() {
        final var voidType = newPrimitive(TypeKind.VOID);
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{},
                voidType,
                List.of()
        );
        assertEquals("()V", result);
    }

    @Test
    void buildMethodSignature_withTypeParam() {
        final var tpe = mockTypeParameter("T", List.of());
        final var tv = mockTypeVariable("T");
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{tpe},
                tv,
                List.of()
        );
        assertEquals("<T:Ljava/lang/Object;>()TT;", result);
    }

    @Test
    void buildMethodSignature_withBoundedTypeParam() {
        final var bound = newDeclared("java.lang.Number");
        final var tpe = mockTypeParameter("T", List.of(bound));
        final var tv = mockTypeVariable("T");
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{tpe},
                tv,
                List.of(tv)
        );
        assertEquals("<T:Ljava/lang/Number;>(TT;)TT;", result);
    }

    @Test
    void buildMethodSignature_multipleTypeParams() {
        final var tpe1 = mockTypeParameter("K", List.of());
        final var tpe2 = mockTypeParameter("V", List.of());
        final var objectType = newDeclared("java.lang.Object");
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{tpe1, tpe2},
                objectType,
                List.of()
        );
        assertEquals("<K:Ljava/lang/Object;V:Ljava/lang/Object;>()Ljava/lang/Object;", result);
    }

    @Test
    void buildMethodSignature_multipleParams() {
        final var stringType = newDeclared("java.lang.String");
        final var intType = newPrimitive(TypeKind.INT);
        final var boolType = newPrimitive(TypeKind.BOOLEAN);
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{},
                boolType,
                List.of(stringType, intType)
        );
        assertEquals("(Ljava/lang/String;I)Z", result);
    }

    @Test
    void buildMethodSignature_paramIsArray() {
        final var stringType = newDeclared("java.lang.String");
        final var arrayType = mock(ArrayType.class);
        when(arrayType.getKind()).thenReturn(TypeKind.ARRAY);
        when(arrayType.getComponentType()).thenReturn(stringType);
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{},
                stringType,
                List.of(arrayType)
        );
        assertEquals("([Ljava/lang/String;)Ljava/lang/String;", result);
    }

    @Test
    void buildMethodSignature_typeParamWithPrimitiveBound() {
        final var bound = newPrimitive(TypeKind.INT);
        final var tpe = mockTypeParameter("T", List.of(bound));
        final var voidType = newPrimitive(TypeKind.VOID);
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{tpe},
                voidType,
                List.of()
        );
        assertEquals("<T:I>()V", result);
    }

    @Test
    void buildMethodSignature_typeParamWithInterfaceBound() {
        final var bound = newDeclared("java.lang.Comparable");
        final var tpe = mockTypeParameter("T", List.of(bound));
        final var tv = mockTypeVariable("T");
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{tpe},
                tv,
                List.of(tv)
        );
        assertEquals("<T:Ljava/lang/Comparable;>(TT;)TT;", result);
    }

    @Test
    void buildMethodSignature_wildcardParam() {
        final var upperBound = newDeclared("java.lang.Number");
        final var wt = mock(WildcardType.class);
        when(wt.getKind()).thenReturn(TypeKind.WILDCARD);
        when(wt.getUpperBound()).thenReturn(upperBound);
        when(wt.getLowerBound()).thenReturn(null);

        final var listType = mock(DeclaredType.class);
        when(listType.getKind()).thenReturn(TypeKind.DECLARED);
        final var listTe = mock(TypeElement.class);
        when(listType.asElement()).thenReturn(listTe);
        when(listTe.getQualifiedName()).thenReturn("java.util.List");
        doReturn(List.of(wt)).when(listType).getTypeArguments();

        final var voidType = newPrimitive(TypeKind.VOID);
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{},
                voidType,
                List.of(listType)
        );
        assertEquals("(Ljava/util/List<+Ljava/lang/Number;>;)V", result);
    }

    @Test
    void buildMethodSignature_nullReturnType() {
        final var result = JvmSignatureBuilder.buildMethodSignature(
                new TypeParameterElement[]{},
                (TypeMirror) null,
                List.of()
        );
        assertEquals("()Ljava/lang/Object;", result);
    }

    // ---------------------------------------------------------------
    // AsmBackend.byteCodeToText
    // ---------------------------------------------------------------

    @Test
    void byteCodeToText_minimalClass() {
        final var bytecode = createMinimalClass("Test", "java/lang/Object");
        final var text = AsmBackend.byteCodeToText(bytecode);
        assertNotNull(text);
        assertTrue(text.contains("Test"));
        assertTrue(text.contains("public class"));
    }

    @Test
    void byteCodeToText_classWithMethod() {
        final var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
                "Hello", null, "java/lang/Object", null);
        cw.visitSource("Hello.nabu", null);
        final var mv = cw.visitMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC,
                "main", "([Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        final var bytecode = cw.toByteArray();

        final var text = AsmBackend.byteCodeToText(bytecode);
        assertNotNull(text);
        assertTrue(text.contains("Hello"));
        assertTrue(text.contains("public static main"));
        assertTrue(text.contains("RETURN"));
    }

    @Test
    void byteCodeToText_classWithFields() {
        final var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
                "WithFields", null, "java/lang/Object", null);
        cw.visitSource("WithFields.nabu", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "count", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitEnd();
        final var bytecode = cw.toByteArray();

        final var text = AsmBackend.byteCodeToText(bytecode);
        assertNotNull(text);
        assertTrue(text.contains("WithFields"));
        assertTrue(text.contains("count"));
        assertTrue(text.contains("name"));
    }

    // ---------------------------------------------------------------
    // AsmBackend.validate
    // ---------------------------------------------------------------

    @Test
    void validate_validBytecode() {
        final var bytecode = createMinimalClass("Valid", "java/lang/Object");
        assertDoesNotThrow(() -> AsmBackend.validate(bytecode));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static String append(final TypeMirror type) {
        final var sb = new StringBuilder();
        JvmSignatureBuilder.appendTypeMirror(sb, type);
        return sb.toString();
    }

    private static TypeMirror newPrimitive(final TypeKind kind) {
        final var tm = mock(TypeMirror.class);
        when(tm.getKind()).thenReturn(kind);
        return tm;
    }

    @SuppressWarnings("unchecked")
    private static DeclaredType newDeclared(final String qualifiedName) {
        final var dt = mock(DeclaredType.class);
        when(dt.getKind()).thenReturn(TypeKind.DECLARED);
        final var te = mock(TypeElement.class);
        when(dt.asElement()).thenReturn(te);
        when(te.getQualifiedName()).thenReturn(qualifiedName);
        when(dt.getTypeArguments()).thenReturn((List) List.of());
        return dt;
    }

    @SuppressWarnings("unchecked")
    private static TypeParameterElement mockTypeParameter(final String name,
                                                           final List<? extends TypeMirror> bounds) {
        final var tpe = mock(TypeParameterElement.class);
        when(tpe.getSimpleName()).thenReturn(name);
        when(tpe.getBounds()).thenReturn((List) new ArrayList<>(bounds));
        return tpe;
    }

    private static TypeVariable mockTypeVariable(final String name) {
        final var tv = mock(TypeVariable.class);
        final var tpe = mock(TypeParameterElement.class);
        when(tv.getKind()).thenReturn(TypeKind.TYPEVAR);
        when(tv.asElement()).thenReturn(tpe);
        when(tpe.getSimpleName()).thenReturn(name);
        return tv;
    }

    private static byte[] createMinimalClass(final String name, final String superName) {
        final var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
                name, null, superName, null);
        cw.visitSource(name + ".nabu", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}

package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.compiler.type.impl.CPrimitiveType;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AsmHelperTest {

    @Test
    void createDescriptorWithValuesPrintsAllParams() {
        var desc = AsmHelper.createDescriptorWithValues(List.of(
                new IRValue.Temp("%a", IRType.I32),
                new IRValue.ConstInt(42, IRType.I64)
        ), IRType.I32);
        assertEquals("(IJ)I", desc);
    }

    @Test
    void createDescriptorWithValuesNoParams() {
        var desc = AsmHelper.createDescriptorWithValues(List.of(), IRType.VOID);
        assertEquals("()V", desc);
    }

    @Test
    void createDescriptorFromParamTypes() {
        assertEquals("(IJ)V", AsmHelper.createDescriptor(List.of(IRType.I32, IRType.I64), IRType.VOID));
        assertEquals("()F", AsmHelper.createDescriptor(List.of(), IRType.F32));
    }

    @Test
    void createDescriptorPrimitives() {
        assertEquals("V", AsmHelper.createDescriptor(IRType.VOID));
        assertEquals("Z", AsmHelper.createDescriptor(IRType.BOOL));
        assertEquals("B", AsmHelper.createDescriptor(new IRType.Int(8)));
        assertEquals("S", AsmHelper.createDescriptor(new IRType.Int(16)));
        assertEquals("I", AsmHelper.createDescriptor(IRType.I32));
        assertEquals("J", AsmHelper.createDescriptor(IRType.I64));
        assertEquals("I", AsmHelper.createDescriptor(new IRType.Int(1)));
        assertEquals("F", AsmHelper.createDescriptor(IRType.F32));
        assertEquals("D", AsmHelper.createDescriptor(IRType.F64));
    }

    @Test
    void createDescriptorPointers() {
        assertEquals("Ljava/lang/String;", AsmHelper.createDescriptor(new IRType.Ptr(IRType.I8, "Ljava/lang/String;")));
        assertEquals("Ljava/lang/invoke/MethodHandle;",
                AsmHelper.createDescriptor(new IRType.Ptr(IRType.fn(IRType.I32))));
        assertEquals("Ljava/lang/String;",
                AsmHelper.createDescriptor(new IRType.Ptr(new IRType.Ptr(IRType.I8))));
        assertEquals("Ljava/lang/Object;", AsmHelper.createDescriptor(new IRType.Ptr(IRType.BOOL)));
    }

    @Test
    void createDescriptorArrayAndFunction() {
        assertEquals("[I", AsmHelper.createDescriptor(new IRType.Array(IRType.I32, 1)));
        assertEquals("[[J", AsmHelper.createDescriptor(new IRType.Array(new IRType.Array(IRType.I64, 1), 2)));
        assertEquals("Ljava/lang/invoke/MethodHandle;", AsmHelper.createDescriptor(IRType.fn(IRType.I32)));
    }

    @Test
    void createDescriptorUnsupportedThrows() {
        assertThrows(UnsupportedOperationException.class,
                () -> AsmHelper.createDescriptor(new IRType.Struct(List.of())));
    }

    @Test
    void toInternalNameArraysAndPrimitives() {
        assertEquals("[I", AsmHelper.toInternalName(new IRType.Array(IRType.I32, 1)));
        assertEquals("V", AsmHelper.toInternalName(IRType.VOID));
        assertEquals("Z", AsmHelper.toInternalName(IRType.BOOL));
        assertEquals("F", AsmHelper.toInternalName(IRType.F32));
        assertEquals("D", AsmHelper.toInternalName(IRType.F64));
        assertEquals("B", AsmHelper.toInternalName(new IRType.Int(8)));
        assertEquals("S", AsmHelper.toInternalName(new IRType.Int(16)));
        assertEquals("I", AsmHelper.toInternalName(IRType.I32));
        assertEquals("J", AsmHelper.toInternalName(IRType.I64));
        assertEquals("I", AsmHelper.toInternalName(new IRType.Int(1)));
    }

    @Test
    void toInternalNamePointers() {
        assertEquals("java/lang/String", AsmHelper.toInternalName(new IRType.Ptr(IRType.I8, "Ljava/lang/String;")));
        assertEquals("[I", AsmHelper.toInternalName(new IRType.Ptr(IRType.I8, "[I")));
        assertEquals("I", AsmHelper.toInternalName(new IRType.Ptr(IRType.I8, "I")));
        assertEquals("java/lang/String", AsmHelper.toInternalName(new IRType.Ptr(IRType.I8)));
        assertEquals("java/lang/Object", AsmHelper.toInternalName(new IRType.Ptr(IRType.I32)));
        assertEquals("java/lang/invoke/MethodHandle",
                AsmHelper.toInternalName(new IRType.Ptr(IRType.fn(IRType.I32))));
        assertEquals("java/lang/Object", AsmHelper.toInternalName(new IRType.Ptr(IRType.BOOL)));
    }

    @Test
    void toInternalNameFunctionAndUnsupported() {
        assertEquals("java/lang/invoke/MethodHandle", AsmHelper.toInternalName(IRType.fn(IRType.I32)));
        assertThrows(UnsupportedOperationException.class,
                () -> AsmHelper.toInternalName(new IRType.Struct(List.of())));
    }

    @Test
    void toInternalNameFromClassName() {
        assertEquals("java/lang/String", AsmHelper.toInternalName("java.lang.String"));
    }

    @Test
    void createDescriptorFromTypeMirrorDeclared() {
        var classSymbol = new ClassSymbol(0, "java.lang.String", null);
        assertEquals("Ljava/lang/String;", AsmHelper.createDescriptor(classSymbol.asType()));
    }

    @Test
    void createDescriptorFromTypeMirrorFallback() {
        assertEquals("Ljava/lang/Object;", AsmHelper.createDescriptor(new CPrimitiveType(TypeKind.INT)));
        assertEquals("Ljava/lang/Object;", AsmHelper.createDescriptor((io.github.potjerodekool.nabu.type.TypeMirror) null));
    }
}
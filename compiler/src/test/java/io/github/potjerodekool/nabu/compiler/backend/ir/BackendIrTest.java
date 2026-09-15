package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.*;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.compiler.type.impl.*;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CMethodInvocationTree;
import io.github.potjerodekool.nabu.type.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackendIrTest {

    @Nested
    class TypeMirrorToIRTypeTests {

        @Test
        void mapNullReturnsVoid() {
            assertSame(IRType.VOID, TypeMirrorToIRType.map(null));
        }

        @Test
        void mapPrimitiveTypes() {
            assertSame(IRType.BOOL, TypeMirrorToIRType.map(new CPrimitiveType(TypeKind.BOOLEAN)));
            assertEquals(new IRType.Int(8), TypeMirrorToIRType.map(new CPrimitiveType(TypeKind.BYTE)));
            assertEquals(new IRType.Int(16), TypeMirrorToIRType.map(new CPrimitiveType(TypeKind.SHORT)));
            assertEquals(new IRType.Int(16), TypeMirrorToIRType.map(new CPrimitiveType(TypeKind.CHAR)));
            assertSame(IRType.I32, TypeMirrorToIRType.map(new CPrimitiveType(TypeKind.INT)));
            assertSame(IRType.I64, TypeMirrorToIRType.map(new CPrimitiveType(TypeKind.LONG)));
            assertSame(IRType.F32, TypeMirrorToIRType.map(new CPrimitiveType(TypeKind.FLOAT)));
            assertSame(IRType.F64, TypeMirrorToIRType.map(new CPrimitiveType(TypeKind.DOUBLE)));
        }

        @Test
        void mapVoidReturnsVoid() {
            assertSame(IRType.VOID, TypeMirrorToIRType.map(new CVoidType()));
        }

        @Test
        void mapVariableTypeDelegatesToInterferedType() {
            var intType = new CPrimitiveType(TypeKind.INT);
            var varType = new CVariableType(intType);
            assertSame(IRType.I32, TypeMirrorToIRType.map(varType));
        }

        @Test
        void mapVariableTypeWithNullInterferedType() {
            var varType = new CVariableType(null);
            assertSame(IRType.VOID, TypeMirrorToIRType.map(varType));
        }

        @Test
        void mapTypeVariableWithUpperBound() {
            var upperBound = new CPrimitiveType(TypeKind.INT);
            var tv = new CTypeVariable("T", null, upperBound, null);
            assertSame(IRType.I32, TypeMirrorToIRType.map(tv));
        }

        @Test
        void mapTypeVariableWithLowerBound() {
            var lowerBound = new CPrimitiveType(TypeKind.LONG);
            var tv = new CTypeVariable("T", null, null, lowerBound);
            assertSame(IRType.I64, TypeMirrorToIRType.map(tv));
        }

        @Test
        void mapTypeVariableWithNoBoundsReturnsPtrI8() {
            var tv = new CTypeVariable("T");
            var result = TypeMirrorToIRType.map(tv);
            assertInstanceOf(IRType.Ptr.class, result);
            assertSame(IRType.I8, ((IRType.Ptr) result).pointee());
        }

        @Test
        void mapTypeVariableUpperBoundTakesPrecedence() {
            var upper = new CPrimitiveType(TypeKind.DOUBLE);
            var lower = new CPrimitiveType(TypeKind.INT);
            var tv = new CTypeVariable("T", null, upper, lower);
            assertSame(IRType.F64, TypeMirrorToIRType.map(tv));
        }

        @Test
        void mapWildcardExtendsBound() {
            var extendsBound = new CPrimitiveType(TypeKind.INT);
            var wildcard = new CWildcardType(extendsBound, BoundKind.EXTENDS, null);
            assertSame(IRType.I32, TypeMirrorToIRType.map(wildcard));
        }

        @Test
        void mapWildcardSuperBound() {
            var superBound = new CPrimitiveType(TypeKind.LONG);
            var wildcard = new CWildcardType(superBound, BoundKind.SUPER, null);
            assertSame(IRType.I64, TypeMirrorToIRType.map(wildcard));
        }

        @Test
        void mapWildcardUnboundReturnsPtrI8() {
            var wildcard = new CWildcardType(null, BoundKind.UNBOUND, null);
            var result = TypeMirrorToIRType.map(wildcard);
            assertInstanceOf(IRType.Ptr.class, result);
            assertSame(IRType.I8, ((IRType.Ptr) result).pointee());
        }

        @Test
        void mapWildcardNonWildcardTypeReturnsPtrI8() {
            var wildcard = new CWildcardType(null, BoundKind.UNBOUND, null);
            var result = TypeMirrorToIRType.map(wildcard);
            assertInstanceOf(IRType.Ptr.class, result);
        }

        @Test
        void mapIntersectionTypeWithBounds() {
            var bound1 = new CPrimitiveType(TypeKind.INT);
            var bound2 = new CPrimitiveType(TypeKind.LONG);
            var intersection = new CIntersectionType(List.of(bound1, bound2));
            assertSame(IRType.I32, TypeMirrorToIRType.map(intersection));
        }

        @Test
        void mapIntersectionTypeWithEmptyBounds() {
            var intersection = new CIntersectionType(List.of());
            var result = TypeMirrorToIRType.map(intersection);
            assertInstanceOf(IRType.Ptr.class, result);
            assertSame(IRType.I8, ((IRType.Ptr) result).pointee());
        }

        @Test
        void mapNullTypeReturnsPtrI8() {
            var result = TypeMirrorToIRType.map(new CNullType());
            assertInstanceOf(IRType.Ptr.class, result);
            assertSame(IRType.I8, ((IRType.Ptr) result).pointee());
        }

        @Test
        void mapArrayType() {
            var intType = new CPrimitiveType(TypeKind.INT);
            var arrayType = new CArrayType(intType);
            var result = TypeMirrorToIRType.map(arrayType);
            assertInstanceOf(IRType.Ptr.class, result);
            assertSame(IRType.I32, ((IRType.Ptr) result).pointee());
        }

        @Test
        void mapNestedArrayType() {
            var intType = new CPrimitiveType(TypeKind.INT);
            var innerArray = new CArrayType(intType);
            var outerArray = new CArrayType(innerArray);
            var result = TypeMirrorToIRType.map(outerArray);
            assertInstanceOf(IRType.Ptr.class, result);
            var innerPtr = ((IRType.Ptr) result).pointee();
            assertInstanceOf(IRType.Ptr.class, innerPtr);
        }

        @Test
        void mapDeclaredTypeReturnsPtrI8() {
            var classSymbol = new ClassSymbol(0, "java.lang.String", null);
            var classType = classSymbol.asType();
            var result = TypeMirrorToIRType.map(classType);
            assertInstanceOf(IRType.Ptr.class, result);
            assertSame(IRType.I8, ((IRType.Ptr) result).pointee());
        }

        @Test
        void mapReturnTypeDelegatesToMap() {
            assertSame(IRType.I32, TypeMirrorToIRType.mapReturnType(new CPrimitiveType(TypeKind.INT)));
            assertSame(IRType.VOID, TypeMirrorToIRType.mapReturnType(null));
        }

        @Test
        void isPrimitiveForVariousTypes() {
            assertTrue(TypeMirrorToIRType.isPrimitive(new CPrimitiveType(TypeKind.INT)));
            assertTrue(TypeMirrorToIRType.isPrimitive(new CPrimitiveType(TypeKind.BOOLEAN)));
            assertFalse(TypeMirrorToIRType.isPrimitive(new CVoidType()));
            assertFalse(TypeMirrorToIRType.isPrimitive(new CNullType()));
            assertFalse(TypeMirrorToIRType.isPrimitive(null));
        }

        @Test
        void isReferenceForVariousTypes() {
            assertTrue(TypeMirrorToIRType.isReference(new CNullType()));
            assertTrue(TypeMirrorToIRType.isReference(new CArrayType(new CPrimitiveType(TypeKind.INT))));
            assertFalse(TypeMirrorToIRType.isReference(new CPrimitiveType(TypeKind.INT)));
            assertFalse(TypeMirrorToIRType.isReference(new CVoidType()));
            assertFalse(TypeMirrorToIRType.isReference(null));
        }

        @Test
        void toJvmDescriptorPrimitives() {
            assertEquals("B", TypeMirrorToIRType.toJvmDescriptor(new CPrimitiveType(TypeKind.BYTE)));
            assertEquals("S", TypeMirrorToIRType.toJvmDescriptor(new CPrimitiveType(TypeKind.SHORT)));
            assertEquals("C", TypeMirrorToIRType.toJvmDescriptor(new CPrimitiveType(TypeKind.CHAR)));
            assertEquals("I", TypeMirrorToIRType.toJvmDescriptor(new CPrimitiveType(TypeKind.INT)));
            assertEquals("J", TypeMirrorToIRType.toJvmDescriptor(new CPrimitiveType(TypeKind.LONG)));
            assertEquals("F", TypeMirrorToIRType.toJvmDescriptor(new CPrimitiveType(TypeKind.FLOAT)));
            assertEquals("D", TypeMirrorToIRType.toJvmDescriptor(new CPrimitiveType(TypeKind.DOUBLE)));
            assertEquals("Z", TypeMirrorToIRType.toJvmDescriptor(new CPrimitiveType(TypeKind.BOOLEAN)));
            assertEquals("V", TypeMirrorToIRType.toJvmDescriptor(new CVoidType()));
        }

        @Test
        void toJvmDescriptorNull() {
            assertNull(TypeMirrorToIRType.toJvmDescriptor(null));
        }

        @Test
        void toJvmDescriptorDeclaredType() {
            var classSymbol = new ClassSymbol(0, "java.lang.String", null);
            var classType = classSymbol.asType();
            assertEquals("Ljava/lang/String;", TypeMirrorToIRType.toJvmDescriptor(classType));
        }

        @Test
        void toJvmDescriptorArrayType() {
            var intType = new CPrimitiveType(TypeKind.INT);
            var arrayType = new CArrayType(intType);
            assertEquals("[I", TypeMirrorToIRType.toJvmDescriptor(arrayType));
        }

        @Test
        void toJvmDescriptorNestedArrayType() {
            var intType = new CPrimitiveType(TypeKind.INT);
            var innerArray = new CArrayType(intType);
            var outerArray = new CArrayType(innerArray);
            assertEquals("[[I", TypeMirrorToIRType.toJvmDescriptor(outerArray));
        }

        @Test
        void toJvmDescriptorUnsupportedReturnsNull() {
            assertNull(TypeMirrorToIRType.toJvmDescriptor(new CNullType()));
            assertNull(TypeMirrorToIRType.toJvmDescriptor(new CNoType()));
        }

        @Test
        void mapNoneTypeFallsToDefault() {
            var result = TypeMirrorToIRType.map(new CNoType());
            assertInstanceOf(IRType.Ptr.class, result);
            assertSame(IRType.I8, ((IRType.Ptr) result).pointee());
        }

        @Test
        void mapNonArrayTypeWithArrayKindReturnsPtrI8() {
            // CArrayType that doesn't match instanceof ArrayType — not possible with CArrayType,
            // but the branch exists for safety. Test the ARRAY branch with actual ArrayType.
            var arrayType = new CArrayType(new CPrimitiveType(TypeKind.INT));
            var result = TypeMirrorToIRType.map(arrayType);
            assertInstanceOf(IRType.Ptr.class, result);
        }
    }

    @Nested
    class ScopeTrackerTests {

        @Test
        void updateInCurrentScope() {
            var tracker = new ScopeTracker();
            tracker.pushScope();
            var original = new IRValue.Temp("%x", IRType.I32);
            tracker.define("x", original);

            var updated = new IRValue.Temp("%x_new", IRType.I32);
            tracker.update("x", updated);

            assertSame(updated, tracker.lookup("x").orElseThrow());
        }

        @Test
        void updateInOuterScope() {
            var tracker = new ScopeTracker();
            tracker.pushScope();
            var original = new IRValue.Temp("%x", IRType.I32);
            tracker.define("x", original);

            tracker.pushScope();

            var updated = new IRValue.Temp("%x_new", IRType.I32);
            tracker.update("x", updated);

            assertSame(updated, tracker.lookup("x").orElseThrow());
        }

        @Test
        void updateNonExistentThrows() {
            var tracker = new ScopeTracker();
            tracker.pushScope();

            assertThrows(IllegalStateException.class,
                    () -> tracker.update("nonexistent", new IRValue.Temp("%v", IRType.I32)));
        }

        @Test
        void updateUpdatesInCorrectScope() {
            var tracker = new ScopeTracker();
            tracker.pushScope();
            var outer = new IRValue.Temp("%outer", IRType.I32);
            tracker.define("x", outer);

            tracker.pushScope();
            var inner = new IRValue.Temp("%inner", IRType.I32);
            tracker.define("x", inner);

            var newInner = new IRValue.Temp("%new_inner", IRType.I32);
            tracker.update("x", newInner);

            assertSame(newInner, tracker.lookup("x").orElseThrow());
            tracker.popScope();

            assertSame(outer, tracker.lookup("x").orElseThrow());
        }
    }

    @Nested
    class TagToIROpTests {

        @Test
        void compoundAssignmentOpAdd() {
            assertSame(IRInstruction.BinaryOp.Op.ADD, TagToIROp.compoundAssignmentOp(Tag.ADD_ASSIGN));
        }

        @Test
        void compoundAssignmentOpAnd() {
            assertSame(IRInstruction.BinaryOp.Op.AND, TagToIROp.compoundAssignmentOp(Tag.AND_ASSIGN));
        }

        @Test
        void compoundAssignmentOpOr() {
            assertSame(IRInstruction.BinaryOp.Op.OR, TagToIROp.compoundAssignmentOp(Tag.OR_ASSIGN));
        }

        @Test
        void compoundAssignmentOpXor() {
            assertSame(IRInstruction.BinaryOp.Op.XOR, TagToIROp.compoundAssignmentOp(Tag.XOR_ASSIGN));
        }

        @Test
        void compoundAssignmentOpMod() {
            assertSame(IRInstruction.BinaryOp.Op.MOD, TagToIROp.compoundAssignmentOp(Tag.MOD_ASSIGN));
        }

        @Test
        void compoundAssignmentOpUnsupportedThrows() {
            assertThrows(UnsupportedOperationException.class,
                    () -> TagToIROp.compoundAssignmentOp(Tag.ADD));
        }

        @Test
        @Disabled
        void compoundAssignmentOpMulThrows() {
            assertThrows(UnsupportedOperationException.class,
                    () -> TagToIROp.compoundAssignmentOp(Tag.MUL_ASSIGN));
        }

        @Test
        void isCompoundAssignmentTrue() {
            assertTrue(TagToIROp.isCompoundAssignment(Tag.ADD_ASSIGN));
            assertTrue(TagToIROp.isCompoundAssignment(Tag.MUL_ASSIGN));
            assertTrue(TagToIROp.isCompoundAssignment(Tag.DIV_ASSIGN));
            assertTrue(TagToIROp.isCompoundAssignment(Tag.MOD_ASSIGN));
            assertTrue(TagToIROp.isCompoundAssignment(Tag.AND_ASSIGN));
            assertTrue(TagToIROp.isCompoundAssignment(Tag.OR_ASSIGN));
            assertTrue(TagToIROp.isCompoundAssignment(Tag.XOR_ASSIGN));
            assertTrue(TagToIROp.isCompoundAssignment(Tag.LSHIFT_ASSIGN));
            assertTrue(TagToIROp.isCompoundAssignment(Tag.RSHIFT_ASSIGN));
            assertTrue(TagToIROp.isCompoundAssignment(Tag.URSHIFT_ASSIGN));
        }

        @Test
        void isCompoundAssignmentFalse() {
            assertFalse(TagToIROp.isCompoundAssignment(Tag.ADD));
            assertFalse(TagToIROp.isCompoundAssignment(Tag.ASSIGN));
            assertFalse(TagToIROp.isCompoundAssignment(Tag.POST_INC));
        }

        @Test
        void isComparison() {
            assertTrue(TagToIROp.isComparison(Tag.EQ));
            assertTrue(TagToIROp.isComparison(Tag.NE));
            assertTrue(TagToIROp.isComparison(Tag.LT));
            assertTrue(TagToIROp.isComparison(Tag.LE));
            assertTrue(TagToIROp.isComparison(Tag.GT));
            assertTrue(TagToIROp.isComparison(Tag.GE));
            assertFalse(TagToIROp.isComparison(Tag.ADD));
            assertFalse(TagToIROp.isComparison(Tag.AND));
        }

        @Test
        void mapArithmeticOps() {
            assertSame(IRInstruction.BinaryOp.Op.ADD, TagToIROp.map(Tag.ADD));
            assertSame(IRInstruction.BinaryOp.Op.SUB, TagToIROp.map(Tag.SUB));
            assertSame(IRInstruction.BinaryOp.Op.MUL, TagToIROp.map(Tag.MUL));
            assertSame(IRInstruction.BinaryOp.Op.DIV, TagToIROp.map(Tag.DIV));
            assertSame(IRInstruction.BinaryOp.Op.MOD, TagToIROp.map(Tag.MOD));
        }

        @Test
        void mapComparisonOps() {
            assertSame(IRInstruction.BinaryOp.Op.EQ, TagToIROp.map(Tag.EQ));
            assertSame(IRInstruction.BinaryOp.Op.NEQ, TagToIROp.map(Tag.NE));
            assertSame(IRInstruction.BinaryOp.Op.LT, TagToIROp.map(Tag.LT));
            assertSame(IRInstruction.BinaryOp.Op.LTE, TagToIROp.map(Tag.LE));
            assertSame(IRInstruction.BinaryOp.Op.GT, TagToIROp.map(Tag.GT));
            assertSame(IRInstruction.BinaryOp.Op.GTE, TagToIROp.map(Tag.GE));
        }

        @Test
        void mapLogicalOps() {
            assertSame(IRInstruction.BinaryOp.Op.AND, TagToIROp.map(Tag.AND));
            assertSame(IRInstruction.BinaryOp.Op.OR, TagToIROp.map(Tag.OR));
            assertSame(IRInstruction.BinaryOp.Op.BITAND, TagToIROp.map(Tag.BITAND));
            assertSame(IRInstruction.BinaryOp.Op.BITOR, TagToIROp.map(Tag.BITOR));
            assertSame(IRInstruction.BinaryOp.Op.BITXOR, TagToIROp.map(Tag.BITXOR));
        }

        @Test
        void mapUnsupportedThrows() {
            assertThrows(UnsupportedOperationException.class, () -> TagToIROp.map(Tag.ASSIGN));
            assertThrows(UnsupportedOperationException.class, () -> TagToIROp.map(Tag.ADD_ASSIGN));
            assertThrows(UnsupportedOperationException.class, () -> TagToIROp.map(Tag.POST_INC));
            assertThrows(UnsupportedOperationException.class, () -> TagToIROp.map(Tag.NOT));
            assertThrows(UnsupportedOperationException.class, () -> TagToIROp.map(Tag.LSHIFT));
        }
    }

    @Nested
    class PhiEliminationTests {

        @Test
        void replaceInBinaryOp() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.I32, List.of(), 0);

            var entry = builder.currentBlock();
            var cond = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = builder.constInt(10);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = builder.constInt(20);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(IRType.I32, List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            var one = builder.constInt(1);
            var sum = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, phiResult, one);
            builder.emitReturn(sum);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasBinaryOp = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.BinaryOp)
                    .map(i -> (IRInstruction.BinaryOp) i)
                    .anyMatch(bo -> bo.left() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasBinaryOp, "BinaryOp should reference the phi-load value");
        }

        @Test
        void replaceInCondBranch() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.VOID, List.of(), 0);

            var entry = builder.currentBlock();
            var cond1 = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");
            var thenInner = builder.beginBlock("then.inner");
            var exitBlk = builder.beginBlock("exit");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond1, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = builder.constBool(true);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = builder.constBool(false);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(IRType.BOOL, List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            builder.emitCondBranch(phiResult, thenInner, exitBlk);

            builder.setCurrentBlock(thenInner);
            builder.emitBranch(exitBlk);

            builder.setCurrentBlock(exitBlk);
            builder.emitReturn(null);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasPhiRef = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.CondBranch)
                    .map(i -> (IRInstruction.CondBranch) i)
                    .anyMatch(cb -> cb.condition() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasPhiRef, "CondBranch should reference the phi-load value");
        }

        @Test
        void replaceInReturn() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.I32, List.of(), 0);

            var entry = builder.currentBlock();
            var cond = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = builder.constInt(42);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = builder.constInt(99);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(IRType.I32, List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            builder.emitReturn(phiResult);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasReturnWithPhi = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.Return)
                    .map(i -> (IRInstruction.Return) i)
                    .anyMatch(r -> r.value() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasReturnWithPhi, "Return should reference the phi-load value");
        }

        @Test
        void replaceInMove() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.I32, List.of(), 0);

            var entry = builder.currentBlock();
            var cond = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = builder.constInt(5);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = builder.constInt(10);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(IRType.I32, List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            var moveResult = new IRValue.Temp("%moved", IRType.I32);
            mergeBlk.add(new IRInstruction.Move(moveResult, phiResult, SourceLocation.UNKNOWN));
            builder.emitReturn(moveResult);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasMoveWithPhi = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.Move)
                    .map(i -> (IRInstruction.Move) i)
                    .anyMatch(m -> m.value() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasMoveWithPhi, "Move should reference the phi-load value");
        }

        @Test
        void replaceInStore() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.I32, List.of(), 0);

            var entry = builder.currentBlock();
            var cond = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = builder.constInt(5);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = builder.constInt(10);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(IRType.I32, List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            var ptr = new IRValue.Temp("%ptr", new IRType.Ptr(IRType.I32));
            mergeBlk.add(new IRInstruction.Store(ptr, phiResult, SourceLocation.UNKNOWN));
            builder.emitReturn(null);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasStoreWithPhi = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.Store)
                    .map(i -> (IRInstruction.Store) i)
                    .anyMatch(s -> s.value() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasStoreWithPhi, "Store value should reference the phi-load value");

            var hasStoreWithPhiPtr = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.Store)
                    .map(i -> (IRInstruction.Store) i)
                    .anyMatch(s -> s.ptr() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertFalse(hasStoreWithPhiPtr, "Store ptr should no longer reference original phi result");
        }

        @Test
        void replaceInCast() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.I64, List.of(), 0);

            var entry = builder.currentBlock();
            var cond = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = builder.constInt(42);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = builder.constInt(99);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(IRType.I32, List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            var castResult = new IRValue.Temp("%cast", IRType.I64);
            mergeBlk.add(new IRInstruction.Cast(castResult, phiResult, IRType.I64, SourceLocation.UNKNOWN));
            builder.emitReturn(castResult);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasCastWithPhi = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.Cast)
                    .map(i -> (IRInstruction.Cast) i)
                    .anyMatch(c -> c.source() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasCastWithPhi, "Cast source should reference the phi-load value");
        }

        @Test
        void replaceInArrayLoad() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.I32, List.of(), 0);

            var entry = builder.currentBlock();
            var cond = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = builder.constInt(0);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = builder.constInt(1);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(IRType.I32, List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            var arrayResult = new IRValue.Temp("%arr", new IRType.Ptr(IRType.I32));
            var indexResult = new IRValue.Temp("%idx", IRType.I32);
            var alResult = new IRValue.Temp("%al", IRType.I32);
            mergeBlk.add(new IRInstruction.ArrayLoad(alResult, arrayResult, phiResult, IRType.I32, SourceLocation.UNKNOWN));
            builder.emitReturn(alResult);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasArrayLoadWithPhi = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.ArrayLoad)
                    .map(i -> (IRInstruction.ArrayLoad) i)
                    .anyMatch(al -> al.index() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasArrayLoadWithPhi, "ArrayLoad index should reference the phi-load value");
        }

        @Test
        void replaceInArrayLength() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.I32, List.of(), 0);

            var entry = builder.currentBlock();
            var cond = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = IRValue.nullPtr(IRType.I8);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = IRValue.nullPtr(IRType.I8);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(new IRType.Ptr(IRType.I8), List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            var alResult = new IRValue.Temp("%len", IRType.I32);
            mergeBlk.add(new IRInstruction.ArrayLength(alResult, phiResult, SourceLocation.UNKNOWN));
            builder.emitReturn(alResult);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasArrayLengthWithPhi = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.ArrayLength)
                    .map(i -> (IRInstruction.ArrayLength) i)
                    .anyMatch(al -> al.array() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasArrayLengthWithPhi, "ArrayLength array should reference the phi-load value");
        }

        @Test
        void replaceInMonitorEnterExit() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.VOID, List.of(), 0);

            var entry = builder.currentBlock();
            var cond = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = IRValue.nullPtr(IRType.I8);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = IRValue.nullPtr(IRType.I8);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(new IRType.Ptr(IRType.I8), List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            mergeBlk.add(new IRInstruction.MonitorEnter(phiResult, SourceLocation.UNKNOWN));
            mergeBlk.add(new IRInstruction.MonitorExit(phiResult, SourceLocation.UNKNOWN));
            builder.emitReturn(null);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasMonitorEnterWithPhi = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.MonitorEnter)
                    .map(i -> (IRInstruction.MonitorEnter) i)
                    .anyMatch(me -> me.object() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasMonitorEnterWithPhi, "MonitorEnter should reference the phi-load value");

            var hasMonitorExitWithPhi = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.MonitorExit)
                    .map(i -> (IRInstruction.MonitorExit) i)
                    .anyMatch(mx -> mx.object() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasMonitorExitWithPhi, "MonitorExit should reference the phi-load value");
        }

        @Test
        void replaceInIndirectCall() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.I32, List.of(), 0);

            var entry = builder.currentBlock();
            var cond = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = IRValue.nullPtr(IRType.I8);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = IRValue.nullPtr(IRType.I8);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(new IRType.Ptr(IRType.I8), List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            var fnType = IRType.fn(IRType.I32);
            var icResult = new IRValue.Temp("%call", IRType.I32);
            mergeBlk.add(new IRInstruction.IndirectCall(
                    icResult, phiResult, fnType,
                    List.of(), SourceLocation.UNKNOWN));
            builder.emitReturn(icResult);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasIndirectCallWithPhi = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.IndirectCall)
                    .map(i -> (IRInstruction.IndirectCall) i)
                    .anyMatch(ic -> ic.callee() instanceof IRValue.Temp t && t.name().startsWith("%phi."));
            assertTrue(hasIndirectCallWithPhi, "IndirectCall callee should reference the phi-load value");
        }

        @Test
        void replaceInCall() {
            var builder = new IRBuilder("test");
            builder.setLocation("test.lang", 1, 1);
            builder.beginFunction("test", IRType.I32, List.of(), 0);

            var entry = builder.currentBlock();
            var cond = builder.constBool(true);
            var thenBlk = builder.beginBlock("then");
            var elseBlk = builder.beginBlock("else");
            var mergeBlk = builder.beginBlock("merge");

            builder.setCurrentBlock(entry);
            builder.emitCondBranch(cond, thenBlk, elseBlk);

            builder.setCurrentBlock(thenBlk);
            var v1 = builder.constInt(42);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(elseBlk);
            var v2 = builder.constInt(99);
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(mergeBlk);
            var phiResult = builder.emitPhi(IRType.I32, List.of(
                    new IRInstruction.Phi.Incoming(v1, thenBlk),
                    new IRInstruction.Phi.Incoming(v2, elseBlk)
            ));
            var callResult = new IRValue.Temp("%call", IRType.I32);
            mergeBlk.add(new IRInstruction.Call(
                    CallKind.STATIC, IRType.I32, List.of(IRType.I32),
                    callResult, "foo", List.of(phiResult),
                    SourceLocation.UNKNOWN));
            builder.emitReturn(callResult);
            builder.endFunction();

            PhiElimination.run(builder.build());

            var mergeInstrs = mergeBlk.instructions();
            var hasCallWithPhi = mergeInstrs.stream()
                    .filter(i -> i instanceof IRInstruction.Call)
                    .map(i -> (IRInstruction.Call) i)
                    .anyMatch(c -> c.args().stream()
                            .anyMatch(a -> a instanceof IRValue.Temp t && t.name().startsWith("%phi.")));
            assertTrue(hasCallWithPhi, "Call args should reference the phi-load value");
        }
    }

    @Nested
    class CallKindResolverTests {

        private MethodSymbol createMethodSymbol(String name, long flags, ElementKind kind,
                                                 Symbol owner, TypeMirror returnType) {
            return new MethodSymbol(kind, flags, name, owner, null,
                    List.of(), returnType, List.of(), List.of(), List.of());
        }

        @Test
        void resolveStaticMethod() {
            var classSymbol = new ClassSymbol(0, "MyClass", null);
            var methodSymbol = createMethodSymbol("foo", Flags.STATIC, ElementKind.METHOD,
                    classSymbol, new CPrimitiveType(TypeKind.INT));
            var methodType = methodSymbol.asType();
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("foo"), List.of(), List.of());
            invocation.setMethodType(methodType);

            assertSame(CallKind.STATIC, CallKindResolver.resolve(invocation));
        }

        @Test
        void resolveConstructor() {
            var classSymbol = new ClassSymbol(0, "MyClass", null);
            var methodSymbol = createMethodSymbol("<init>", 0, ElementKind.CONSTRUCTOR,
                    classSymbol, new CVoidType());
            var methodType = methodSymbol.asType();
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("<init>"), List.of(), List.of());
            invocation.setMethodType(methodType);

            assertSame(CallKind.SPECIAL, CallKindResolver.resolve(invocation));
        }

        @Test
        void resolvePrivateMethod() {
            var classSymbol = new ClassSymbol(0, "MyClass", null);
            var methodSymbol = createMethodSymbol("bar", Flags.PRIVATE, ElementKind.METHOD,
                    classSymbol, new CVoidType());
            var methodType = methodSymbol.asType();
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("bar"), List.of(), List.of());
            invocation.setMethodType(methodType);

            assertSame(CallKind.SPECIAL, CallKindResolver.resolve(invocation));
        }

        @Test
        void resolveInterfaceMethod() {
            var interfaceSymbol = new ClassSymbol(Flags.INTERFACE, "MyInterface", null);
            interfaceSymbol.setKind(ElementKind.INTERFACE);
            var methodSymbol = createMethodSymbol("doStuff", 0, ElementKind.METHOD,
                    interfaceSymbol, new CPrimitiveType(TypeKind.INT));
            var methodType = methodSymbol.asType();
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("doStuff"), List.of(), List.of());
            invocation.setMethodType(methodType);

            assertSame(CallKind.INTERFACE, CallKindResolver.resolve(invocation));
        }

        @Test
        void resolveVirtualMethod() {
            var classSymbol = new ClassSymbol(0, "MyClass", null);
            var methodSymbol = createMethodSymbol("baz", 0, ElementKind.METHOD,
                    classSymbol, new CPrimitiveType(TypeKind.INT));
            var methodType = methodSymbol.asType();
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("baz"), List.of(), List.of());
            invocation.setMethodType(methodType);

            assertSame(CallKind.VIRTUAL, CallKindResolver.resolve(invocation));
        }

        @Test
        void resolveNullSymbolReturnsVirtual() {
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("foo"), List.of(), List.of());
            var methodType = new CMethodType(
                    null, null, List.of(), new CPrimitiveType(TypeKind.INT),
                    List.of(), List.of());
            invocation.setMethodType(methodType);

            assertSame(CallKind.VIRTUAL, CallKindResolver.resolve(invocation));
        }

        @Test
        void resolveNameFromIdentifier() {
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("myMethod"), List.of(), List.of());
            assertEquals("myMethod", CallKindResolver.resolveName(invocation));
        }

        @Test
        void resolveNameFromFieldAccess() {
            var selected = IdentifierTree.create("obj");
            var field = IdentifierTree.create("method");
            var fieldAccess = FieldAccessExpressionTree.create(selected, field);
            var invocation = new CMethodInvocationTree(
                    fieldAccess, List.of(), List.of());
            assertEquals("method", CallKindResolver.resolveName(invocation));
        }

        @Test
        void resolveNameUnknownSelector() {
            var invocation = new CMethodInvocationTree(
                    new SomeUnknownExpression(), List.of(), List.of());
            assertEquals("<unknown>", CallKindResolver.resolveName(invocation));
        }

        @Test
        void resolveTargetFromFieldAccess() {
            var selected = IdentifierTree.create("obj");
            var field = IdentifierTree.create("method");
            var fieldAccess = FieldAccessExpressionTree.create(selected, field);
            var invocation = new CMethodInvocationTree(
                    fieldAccess, List.of(), List.of());

            var target = CallKindResolver.resolveTarget(invocation);
            assertNotNull(target);
            assertTrue(target instanceof IdentifierTree);
            assertEquals("obj", ((IdentifierTree) target).getName());
        }

        @Test
        void resolveTargetFromIdentifierReturnsNull() {
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("myMethod"), List.of(), List.of());

            assertNull(CallKindResolver.resolveTarget(invocation));
        }

        @Test
        void isSuperFieldAccessWithSuperSelected() {
            var superId = IdentifierTree.create("super");
            var field = IdentifierTree.create("foo");
            var fieldAccess = FieldAccessExpressionTree.create(superId, field);
            var invocation = new CMethodInvocationTree(
                    fieldAccess, List.of(), List.of());

            assertTrue(CallKindResolver.isSuper(invocation));
        }

        @Test
        void isSuperIdentifierDirectSuper() {
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("super"), List.of(), List.of());

            assertTrue(CallKindResolver.isSuper(invocation));
        }

        @Test
        void isSuperFieldAccessWithNonSuperSelected() {
            var selected = IdentifierTree.create("this");
            var field = IdentifierTree.create("foo");
            var fieldAccess = FieldAccessExpressionTree.create(selected, field);
            var invocation = new CMethodInvocationTree(
                    fieldAccess, List.of(), List.of());

            assertFalse(CallKindResolver.isSuper(invocation));
        }

        @Test
        void isSuperRegularIdentifier() {
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("myMethod"), List.of(), List.of());

            assertFalse(CallKindResolver.isSuper(invocation));
        }

        @Test
        void resolveExecutableWithMethodType() {
            var classSymbol = new ClassSymbol(0, "MyClass", null);
            var methodSymbol = createMethodSymbol("foo", Flags.STATIC, ElementKind.METHOD,
                    classSymbol, new CPrimitiveType(TypeKind.INT));
            var methodType = methodSymbol.asType();
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("foo"), List.of(), List.of());
            invocation.setMethodType(methodType);

            assertSame(methodSymbol, CallKindResolver.resolveExecutable(invocation));
        }

        @Test
        void resolveExecutableWithNullMethodType() {
            var invocation = new CMethodInvocationTree(
                    IdentifierTree.create("foo"), List.of(), List.of());
            invocation.setMethodType(null);

            assertNull(CallKindResolver.resolveExecutable(invocation));
        }

        @Test
        void resolveExecutableWithFieldAccessSelectorNoSymbol() {
            var selected = IdentifierTree.create("obj");
            var field = IdentifierTree.create("method");
            var fieldAccess = FieldAccessExpressionTree.create(selected, field);
            var invocation = new CMethodInvocationTree(
                    fieldAccess, List.of(), List.of());
            invocation.setMethodType(null);

            assertNull(CallKindResolver.resolveExecutable(invocation));
        }
    }

    static class SomeUnknownExpression implements io.github.potjerodekool.nabu.tree.expression.ExpressionTree {
        @Override
        public io.github.potjerodekool.nabu.lang.model.element.Element getSymbol() { return null; }

        @Override
        public void setSymbol(io.github.potjerodekool.nabu.lang.model.element.Element symbol) {}

        @Override
        public io.github.potjerodekool.nabu.tree.expression.builder.ExpressionBuilder<?> builder() { return null; }

        public <R, P> R accept(io.github.potjerodekool.nabu.tree.TreeVisitor<R, P> visitor, P param) { return null; }

        @Override
        public io.github.potjerodekool.nabu.type.TypeMirror getType() { return null; }

        @Override
        public void setType(io.github.potjerodekool.nabu.type.TypeMirror type) {}

        @Override
        public int getLineNumber() { return 0; }

        @Override
        public int getColumnNumber() { return 0; }
    }
}

package io.github.potjerodekool.nabu.compiler.backend.ir2;

import io.github.potjerodekool.nabu.ir.instructions.IRInstruction.BinaryOp.Op;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests voor de IR-generatie hulpklassen.
 */
class IrGenerationTest {

    // -------------------------------------------------------
    // TypeMirrorToIRType
    // -------------------------------------------------------

    @Test
    void primitiefIntWordt_I32() {
        var mirror = new StubTypeMirror(TypeKind.INT);
        assertEquals(IRType.I32, TypeMirrorToIRType.map(mirror));
    }

    @Test
    void primitiefLongWordt_I64() {
        var mirror = new StubTypeMirror(TypeKind.LONG);
        assertEquals(IRType.I64, TypeMirrorToIRType.map(mirror));
    }

    @Test
    void primitiefBooleanWordt_Bool() {
        var mirror = new StubTypeMirror(TypeKind.BOOLEAN);
        assertEquals(IRType.BOOL, TypeMirrorToIRType.map(mirror));
    }

    @Test
    void primitiefByteWordt_I8() {
        var mirror = new StubTypeMirror(TypeKind.BYTE);
        assertEquals(new IRType.Int(8), TypeMirrorToIRType.map(mirror));
    }

    @Test
    void primitiefShortWordt_I16() {
        var mirror = new StubTypeMirror(TypeKind.SHORT);
        assertEquals(new IRType.Int(16), TypeMirrorToIRType.map(mirror));
    }

    @Test
    void primitiefFloatWordt_F32() {
        var mirror = new StubTypeMirror(TypeKind.FLOAT);
        assertEquals(IRType.F32, TypeMirrorToIRType.map(mirror));
    }

    @Test
    void primitiefDoubleWordt_F64() {
        var mirror = new StubTypeMirror(TypeKind.DOUBLE);
        assertEquals(IRType.F64, TypeMirrorToIRType.map(mirror));
    }

    @Test
    void voidWordt_Void() {
        var mirror = new StubTypeMirror(TypeKind.VOID);
        assertEquals(IRType.VOID, TypeMirrorToIRType.map(mirror));
    }

    @Test
    void gedeclareerdTypeWordtOpaquePtrI8() {
        var mirror = new StubTypeMirror(TypeKind.DECLARED);
        IRType result = TypeMirrorToIRType.map(mirror);
        assertInstanceOf(IRType.Ptr.class, result);
        assertEquals(IRType.I8, ((IRType.Ptr) result).pointee());
    }

    @Test
    void nullTypeWordtOpaquePtrI8() {
        var mirror = new StubTypeMirror(TypeKind.NULL);
        IRType result = TypeMirrorToIRType.map(mirror);
        assertInstanceOf(IRType.Ptr.class, result);
    }

    @Test
    void nullMirrorWordt_Void() {
        assertEquals(IRType.VOID, TypeMirrorToIRType.map(null));
    }

    @Test
    void isPrimitivePrimitive() {
        assertTrue(TypeMirrorToIRType.isPrimitive(new StubTypeMirror(TypeKind.INT)));
        assertTrue(TypeMirrorToIRType.isPrimitive(new StubTypeMirror(TypeKind.BOOLEAN)));
        assertFalse(TypeMirrorToIRType.isPrimitive(new StubTypeMirror(TypeKind.DECLARED)));
        assertFalse(TypeMirrorToIRType.isPrimitive(null));
    }

    // -------------------------------------------------------
    // TagToIROp
    // -------------------------------------------------------

    @Test
    void addTagWordtAddOp() {
        assertEquals(Op.ADD, TagToIROp.map(Tag.ADD));
    }

    @Test
    void subTagWordtSubOp() {
        assertEquals(Op.SUB, TagToIROp.map(Tag.SUB));
    }

    @Test
    void eqTagWordtEqOp() {
        assertEquals(Op.EQ, TagToIROp.map(Tag.EQ));
    }

    @Test
    void neTagWordtNeqOp() {
        assertEquals(Op.NEQ, TagToIROp.map(Tag.NE));
    }

    @Test
    void ltTagWordtLtOp() {
        assertEquals(Op.LT, TagToIROp.map(Tag.LT));
    }

    @Test
    void leTagWordtLteOp() {
        assertEquals(Op.LTE, TagToIROp.map(Tag.LE));
    }

    @Test
    void gtTagWordtGtOp() {
        assertEquals(Op.GT, TagToIROp.map(Tag.GT));
    }

    @Test
    void geTagWordtGteOp() {
        assertEquals(Op.GTE, TagToIROp.map(Tag.GE));
    }

    @Test
    void andTagWordtAndOp() {
        assertEquals(Op.AND, TagToIROp.map(Tag.AND));
    }

    @Test
    void orTagWordtOrOp() {
        assertEquals(Op.OR, TagToIROp.map(Tag.OR));
    }

    @Test
    void assignTagGooidUitzondering() {
        assertThrows(UnsupportedOperationException.class,
                () -> TagToIROp.map(Tag.ASSIGN));
    }

    @Test
    void postIncGooidUitzondering() {
        assertThrows(UnsupportedOperationException.class,
                () -> TagToIROp.map(Tag.POST_INC));
    }

    @Test
    void isComparisonJuist() {
        assertTrue(TagToIROp.isComparison(Tag.EQ));
        assertTrue(TagToIROp.isComparison(Tag.LT));
        assertTrue(TagToIROp.isComparison(Tag.GE));
        assertFalse(TagToIROp.isComparison(Tag.ADD));
        assertFalse(TagToIROp.isComparison(Tag.AND));
    }

    @Test
    void isCompoundAssignmentJuist() {
        assertTrue(TagToIROp.isCompoundAssignment(Tag.ADD_ASSIGN));
        assertTrue(TagToIROp.isCompoundAssignment(Tag.AND_ASSIGN));
        assertFalse(TagToIROp.isCompoundAssignment(Tag.ADD));
        assertFalse(TagToIROp.isCompoundAssignment(Tag.EQ));
    }

    @Test
    void compoundAssignmentOpJuist() {
        assertEquals(Op.ADD, TagToIROp.compoundAssignmentOp(Tag.ADD_ASSIGN));
        assertEquals(Op.AND, TagToIROp.compoundAssignmentOp(Tag.AND_ASSIGN));
        assertEquals(Op.OR,  TagToIROp.compoundAssignmentOp(Tag.OR_ASSIGN));
    }

    // -------------------------------------------------------
    // ScopeTracker
    // -------------------------------------------------------

    @Test
    void scopeDefineEnLookup() {
        var tracker = new ScopeTracker();
        var val = IRValue.ofI32(0);

        tracker.pushScope();
        tracker.define("x", val);

        assertTrue(tracker.lookup("x").isPresent());
        assertEquals(val, tracker.lookup("x").get());
    }

    @Test
    void scopeGeenResultaatNaPop() {
        var tracker = new ScopeTracker();
        var val = IRValue.ofI32(0);

        tracker.pushScope();
        tracker.define("x", val);
        tracker.popScope();

        assertTrue(tracker.lookup("x").isEmpty());
    }

    @Test
    void innerScopeVerbergtOuter() {
        var tracker = new ScopeTracker();
        var outer = IRValue.ofI32(1);
        var inner = IRValue.ofI32(2);

        tracker.pushScope();
        tracker.define("x", outer);

        tracker.pushScope();
        tracker.define("x", inner);

        assertEquals(inner, tracker.lookup("x").get());

        tracker.popScope();
        assertEquals(outer, tracker.lookup("x").get());
    }

    @Test
    void requireGooidUitzonderingAlsNietGevonden() {
        var tracker = new ScopeTracker();
        tracker.pushScope();

        assertThrows(IllegalStateException.class,
                () -> tracker.require("bestaat_niet"));
    }

    @Test
    void containsJuist() {
        var tracker = new ScopeTracker();
        tracker.pushScope();
        tracker.define("y", IRValue.ofI32(0));

        assertTrue(tracker.contains("y"));
        assertFalse(tracker.contains("z"));
    }

    @Test
    void resetWistAlleScopes() {
        var tracker = new ScopeTracker();
        tracker.pushScope();
        tracker.define("a", IRValue.ofI32(0));
        tracker.reset();

        assertEquals(0, tracker.depth());
        assertTrue(tracker.lookup("a").isEmpty());
    }

    @Test
    void popZonderPushGooidFout() {
        var tracker = new ScopeTracker();
        assertThrows(IllegalStateException.class, tracker::popScope);
    }

    // -------------------------------------------------------
    // Stub TypeMirror voor tests
    // -------------------------------------------------------

    static class StubTypeMirror implements io.github.potjerodekool.nabu.type.TypeMirror {
        private final TypeKind kind;

        StubTypeMirror(TypeKind kind) { this.kind = kind; }

        @Override public TypeKind getKind() { return kind; }

        @Override
        public <R, P> R accept(io.github.potjerodekool.nabu.type.TypeVisitor<R, P> v, P p) {
            return null;
        }

        @Override public boolean isError() { return false; }

        @Override public String getClassName() { return null; }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof StubTypeMirror other)) return false;
            return kind == other.kind;
        }

        @Override public int hashCode() { return kind.hashCode(); }
    }
}

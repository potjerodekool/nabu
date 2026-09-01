package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.type.impl.CPrimitiveType;
import io.github.potjerodekool.nabu.lang.model.element.CClassAttribute;
import io.github.potjerodekool.nabu.lang.model.element.CConstantAttribute;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JAttributeAdapterTest {

    private static final class Recorder implements AnnotationValueVisitor<Void, List<String>> {

        @Override
        public Void visit(final AnnotationValue annotationValue,
                          final List<String> seen) {
            return null;
        }

        @Override
        public Void visitBoolean(final boolean value,
                                 final List<String> seen) {
            seen.add("boolean");
            return null;
        }

        @Override
        public Void visitByte(final byte value,
                              final List<String> seen) {
            seen.add("byte");
            return null;
        }

        @Override
        public Void visitChar(final char value,
                              final List<String> seen) {
            seen.add("char");
            return null;
        }

        @Override
        public Void visitDouble(final double value,
                                final List<String> seen) {
            seen.add("double");
            return null;
        }

        @Override
        public Void visitFloat(final float value,
                               final List<String> seen) {
            seen.add("float");
            return null;
        }

        @Override
        public Void visitInt(final int value,
                             final List<String> seen) {
            seen.add("int");
            return null;
        }

        @Override
        public Void visitLong(final long value,
                              final List<String> seen) {
            seen.add("long");
            return null;
        }

        @Override
        public Void visitShort(final short value,
                               final List<String> seen) {
            seen.add("short");
            return null;
        }

        @Override
        public Void visitString(final String value,
                                final List<String> seen) {
            seen.add("string");
            return null;
        }

        @Override
        public Void visitType(final TypeMirror typeMirror,
                              final List<String> seen) {
            seen.add("type");
            return null;
        }

        @Override
        public Void visitEnumConstant(final VariableElement variableElement,
                                      final List<String> seen) {
            return null;
        }

        @Override
        public Void visitAnnotation(final AnnotationMirror annotationMirror,
                                    final List<String> seen) {
            return null;
        }

        @Override
        public Void visitArray(final List<? extends AnnotationValue> annotationValues,
                               final List<String> seen) {
            return null;
        }

        @Override
        public Void visitUnknown(final AnnotationValue annotationValue,
                                 final List<String> seen) {
            return null;
        }
    }

    private void assertDispatch(final Object value,
                                final String expected) {
        final var seen = new ArrayList<String>();
        new JConstantAttribute(new CConstantAttribute(value)).accept(new Recorder(), seen);
        assertEquals(List.of(expected), seen);
    }

    @Test
    void constantAttributeDispatchesBoolean() {
        assertDispatch(Boolean.TRUE, "boolean");
    }

    @Test
    void constantAttributeDispatchesByte() {
        assertDispatch((byte) 2, "byte");
    }

    @Test
    void constantAttributeDispatchesChar() {
        assertDispatch('x', "char");
    }

    @Test
    void constantAttributeDispatchesDouble() {
        assertDispatch(1.5D, "double");
    }

    @Test
    void constantAttributeDispatchesFloat() {
        assertDispatch(1.5F, "float");
    }

    @Test
    void constantAttributeDispatchesInt() {
        assertDispatch(1, "int");
    }

    @Test
    void constantAttributeDispatchesShort() {
        assertDispatch((short) 2, "short");
    }

    @Test
    void constantAttributeDispatchesString() {
        assertDispatch("hello", "string");
    }

    @Test
    void constantAttributeDispatchesLong() {
        assertDispatch(1L, "long");
    }

    @Test
    void constantAttributeWithUnknownValueThrows() {
        assertThrows(UnsupportedOperationException.class,
                () -> new JConstantAttribute(new CConstantAttribute(new Object()))
                        .accept(new Recorder(), new ArrayList<>()));
    }

    @Test
    void classAttributeWrapsType() {
        final var wrapped = new JClassAttribute(new CClassAttribute(new CPrimitiveType(TypeKind.INT)));

        assertEquals(javax.lang.model.type.TypeKind.INT, wrapped.getValue().getKind());
        assertNotNull(wrapped.getValue());

        final var seen = new ArrayList<String>();
        wrapped.accept(new Recorder(), seen);
        assertEquals(List.of("type"), seen);
    }

    @Test
    void annotationMirrorReturnsFixedValues() {
        final var mirror = new JAnnotationMirror(null);

        assertNull(mirror.getAnnotationType());
        assertTrue(mirror.getElementValues().isEmpty());
    }
}
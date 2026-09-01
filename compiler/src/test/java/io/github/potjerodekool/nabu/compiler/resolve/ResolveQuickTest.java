package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.lang.model.element.AnnotationValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResolveQuickTest {

    @Test
    void visitBooleanDelegatesToVisitUnknown() {
        final var visitor = new TrackingVisitor<>();
        final var result = visitor.visitBoolean(true, "param");
        assertEquals("unknown", result);
        assertNotNull(visitor.lastValue);
        assertEquals(true, visitor.lastValue.getValue());
        assertEquals("param", visitor.lastParam);
    }

    @Test
    void visitByteDelegatesToVisitUnknown() {
        final var visitor = new TrackingVisitor<>();
        final var result = visitor.visitByte((byte) 42, "param");
        assertEquals("unknown", result);
        assertEquals((byte) 42, visitor.lastValue.getValue());
    }

    @Test
    void visitCharDelegatesToVisitUnknown() {
        final var visitor = new TrackingVisitor<>();
        final var result = visitor.visitChar('A', "param");
        assertEquals("unknown", result);
        assertEquals('A', visitor.lastValue.getValue());
    }

    @Test
    void visitDoubleDelegatesToVisitUnknown() {
        final var visitor = new TrackingVisitor<>();
        final var result = visitor.visitDouble(3.14, null);
        assertEquals("unknown", result);
        assertEquals(3.14, visitor.lastValue.getValue());
    }

    @Test
    void visitFloatDelegatesToVisitUnknown() {
        final var visitor = new TrackingVisitor<>();
        final var result = visitor.visitFloat(2.71f, "p");
        assertEquals("unknown", result);
        assertEquals(2.71f, visitor.lastValue.getValue());
    }

    @Test
    void visitIntDelegatesToVisitUnknown() {
        final var visitor = new TrackingVisitor<>();
        final var result = visitor.visitInt(99, "p");
        assertEquals("unknown", result);
        assertEquals(99, visitor.lastValue.getValue());
    }

    @Test
    void visitLongDelegatesToVisitUnknown() {
        final var visitor = new TrackingVisitor<>();
        final var result = visitor.visitLong(100L, "p");
        assertEquals("unknown", result);
        assertEquals(100L, visitor.lastValue.getValue());
    }

    @Test
    void visitShortDelegatesToVisitUnknown() {
        final var visitor = new TrackingVisitor<>();
        final var result = visitor.visitShort((short) 7, "p");
        assertEquals("unknown", result);
        assertEquals((short) 7, visitor.lastValue.getValue());
    }

    @Test
    void visitStringDelegatesToVisitUnknown() {
        final var visitor = new TrackingVisitor<>();
        final var result = visitor.visitString("hello", "p");
        assertEquals("unknown", result);
        assertEquals("hello", visitor.lastValue.getValue());
    }

    private static class TrackingVisitor<P> extends AbstractAnnotationValueVisitor<String, P> {
        AnnotationValue lastValue;
        P lastParam;

        @Override
        public String visitUnknown(AnnotationValue av, P p) {
            lastValue = av;
            lastParam = p;
            return "unknown";
        }
    }
}

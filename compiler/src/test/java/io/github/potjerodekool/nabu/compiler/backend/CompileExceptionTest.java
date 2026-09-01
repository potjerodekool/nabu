package io.github.potjerodekool.nabu.compiler.backend;

import io.github.potjerodekool.nabu.util.CompileException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompileExceptionTest {

    @Test
    void messageConstructor() {
        final var ex = new CompileException("compile failed");
        assertEquals("compile failed", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void messageAndCauseConstructor() {
        final var cause = new RuntimeException("root cause");
        final var ex = new CompileException("compile failed", cause);
        assertEquals("compile failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void isException() {
        assertInstanceOf(Exception.class, new CompileException("msg"));
    }
}

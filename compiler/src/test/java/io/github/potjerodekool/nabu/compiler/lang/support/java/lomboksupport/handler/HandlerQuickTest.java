package io.github.potjerodekool.nabu.compiler.lang.support.java.lomboksupport.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.type.TypeMirror;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HandlerQuickTest {

    @Test
    void upperFirstMultiChar() {
        final var handler = new StubHandler();
        assertEquals("Name", handler.exposeUpperFirst("name"));
    }

    @Test
    void upperFirstAlreadyUpper() {
        final var handler = new StubHandler();
        assertEquals("Name", handler.exposeUpperFirst("Name"));
    }

    @Test
    void upperFirstSingleChar() {
        final var handler = new StubHandler();
        assertEquals("A", handler.exposeUpperFirst("a"));
    }

    @Test
    void upperFirstSingleCharAlreadyUpper() {
        final var handler = new StubHandler();
        assertEquals("Z", handler.exposeUpperFirst("Z"));
    }

    @Test
    void annotationHandlerDefaultHandleClassDoesNotThrow() {
        final AnnotationHandler handler = new StubHandler();
        handler.handle((ClassSymbol) null);
    }

    @Test
    void annotationHandlerDefaultHandleFieldDoesNotThrow() {
        final AnnotationHandler handler = new StubHandler();
        handler.handle((VariableSymbol) null, null);
    }

    private static class StubHandler extends AbstractAccessorAnnotationHandler2 {

        String exposeUpperFirst(String value) {
            return upperFirst(value);
        }

        @Override
        protected Optional<ExecutableElement> findAccessorMethod(String fieldName, TypeMirror fieldType, ClassSymbol classDeclaration) {
            return Optional.empty();
        }

        @Override
        protected void addAccessorMethod(VariableElement field, long accessLevel, ClassSymbol classDeclaration) {
        }

        @Override
        public String getAnnotationName() {
            return "";
        }
    }
}

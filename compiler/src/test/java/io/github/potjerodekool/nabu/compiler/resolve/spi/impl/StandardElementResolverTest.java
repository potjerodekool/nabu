package io.github.potjerodekool.nabu.compiler.resolve.spi.impl;

import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StandardElementResolverTest {

    private final StandardElementResolver resolver = new StandardElementResolver();

    @Test
    void supportsAlwaysReturnsTrue() {
        assertTrue(resolver.supports(null, null, null));
    }

    @Test
    void resolveReturnsNullForNonDeclaredType() {
        final var result = resolver.resolve("field", null);
        assertNull(result);
    }

    @Test
    void resolveReturnsNullWhenFieldNotFound() {
        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .simpleName("MyClass")
                .flags(Flags.PUBLIC)
                .build();

        final var type = clazz.asType();

        final var result = resolver.resolve("nonExistentField", type);
        assertNull(result);
    }

    @Test
    void resolveFindsFieldByName() {
        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .simpleName("MyClass")
                .flags(Flags.PUBLIC)
                .build();

        final var field = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName("myField")
                .flags(Flags.PUBLIC)
                .build();

        clazz.addEnclosedElement(field);

        final var type = clazz.asType();

        final var result = resolver.resolve("myField", type);
        assertNotNull(result);
        assertEquals("myField", result.getSimpleName().toString());
    }
}

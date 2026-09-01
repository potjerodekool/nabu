package io.github.potjerodekool.nabu.compiler.resolve.access;

import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StandardAccessCheckerTest {

    private final StandardAccessChecker checker = StandardAccessChecker.INSTANCE;

    private TypeElement createClass(String name) {
        return new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .simpleName(name)
                .flags(Flags.PUBLIC)
                .build();
    }

    @Test
    void isAccessibleReturnsTrueForNonVariableElement() {
        final var clazz = createClass("MyClass");
        assertTrue(checker.isAccessible(clazz, clazz));
    }

    @Test
    void isAccessibleReturnsTrueForLocalVariable() {
        final var classSymbol = createClass("MyClass");
        final var local = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("x")
                .build();
        assertTrue(checker.isAccessible(local, classSymbol));
    }

    @Test
    void isAccessibleReturnsTrueForPublicField() {
        final var declaringClass = createClass("OtherClass");
        final var classSymbol = createClass("MyClass");

        final var field = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName("value")
                .flags(Flags.PUBLIC)
                .enclosingElement(declaringClass)
                .build();

        assertTrue(checker.isAccessible(field, classSymbol));
    }

    @Test
    void isAccessibleReturnsTrueForProtectedFieldInSameClass() {
        final var classSymbol = createClass("MyClass");

        final var field = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName("value")
                .flags(Flags.PROTECTED)
                .enclosingElement(classSymbol)
                .build();

        assertTrue(checker.isAccessible(field, classSymbol));
    }

    @Test
    void isAccessibleReturnsTrueForPrivateFieldInSameClass() {
        final var classSymbol = createClass("MyClass");

        final var field = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName("value")
                .flags(Flags.PRIVATE)
                .enclosingElement(classSymbol)
                .build();

        assertTrue(checker.isAccessible(field, classSymbol));
    }

    @Test
    void isAccessibleReturnsFalseForPrivateFieldInDifferentClass() {
        final var declaringClass = createClass("OtherClass");
        final var classSymbol = createClass("MyClass");

        final var field = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName("value")
                .flags(Flags.PRIVATE)
                .enclosingElement(declaringClass)
                .build();

        assertFalse(checker.isAccessible(field, classSymbol));
    }

    @Test
    void isAccessibleReturnsTrueForParameter() {
        final var classSymbol = createClass("MyClass");
        final var param = new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .simpleName("x")
                .build();

        assertTrue(checker.isAccessible(param, classSymbol));
    }
}

package io.github.potjerodekool.nabu.compiler.resolve.method;

import io.github.potjerodekool.nabu.compiler.TestClassElementLoader;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CandidateMatcherTest {

    private final TestClassElementLoader loader = new TestClassElementLoader();

    @Test
    void visitDeclaredTypeSameType() {
        final var objectClass = loader.loadClass(null, "java.lang.Object");
        final var numberClass = loader.loadClass(null, "java.lang.Number");
        final var integerClass = loader.loadClass(null, Constants.INTEGER);
        integerClass.setSuperClass(numberClass.asType());
        final var stringClass = loader.loadClass(null, Constants.STRING);
        stringClass.setSuperClass(objectClass.asType());

        final var matcher = new CandidateMatcher(loader.getTypes());
        matcher.setArgumentType(stringClass.asType());

        assertTrue(objectClass.asType().accept(matcher, stringClass.asType()));
    }

    @Test
    void visitDeclaredTypeSubType() {
        final var objectClass = loader.loadClass(null, "java.lang.Object");
        final var numberClass = loader.loadClass(null, "java.lang.Number");
        numberClass.setSuperClass(objectClass.asType());
        final var integerClass = loader.loadClass(null, Constants.INTEGER);
        integerClass.setSuperClass(numberClass.asType());

        final var matcher = new CandidateMatcher(loader.getTypes());
        matcher.setArgumentType(integerClass.asType());

        assertTrue(numberClass.asType().accept(matcher, objectClass.asType()));
    }
}
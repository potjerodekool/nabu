package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateMatcherTest extends AbstractCompilerTest {

    @Test
    void visitDeclaredTypeSameType() {
        final var objectClass = getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.Object");
        final var numberClass = getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.Number");
        final var integerClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(null, Constants.INTEGER);
        integerClass.setSuperClass(numberClass.asType());
        final var stringClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(null, Constants.STRING);
        stringClass.setSuperClass(objectClass.asType());

        final var matcher = new CandidateMatcher(getCompilerContext().getTypes());
        matcher.setArgumentType(stringClass.asType());

        assertTrue(objectClass.asType().accept(matcher, stringClass.asType()));
    }

    @Test
    void visitDeclaredTypeSubType() {
        final var objectClass = getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.Object");
        final var numberClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.Number");
        numberClass.setSuperClass(objectClass.asType());
        final var integerClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(null, Constants.INTEGER);
        integerClass.setSuperClass(numberClass.asType());

        final var matcher = new CandidateMatcher(getCompilerContext().getTypes());
        matcher.setArgumentType(integerClass.asType());

        assertTrue(numberClass.asType().accept(matcher, objectClass.asType()));
    }
}
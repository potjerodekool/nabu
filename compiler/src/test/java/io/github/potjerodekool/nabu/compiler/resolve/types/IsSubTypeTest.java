package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.CWildcardType;
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.type.BoundKind;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IsSubTypeTest extends AbstractCompilerTest {

    private Types types;
    private IsSubType isSubType;

    @BeforeEach
    void setUp() {
        types = getCompilerContext().getTypes();
        isSubType = new IsSubType((TypesImpl) types);
    }

    @Test
    void visitWildcardType() {
        final var objectType = getCompilerContext().getClassElementLoader().loadClass(null, Constants.OBJECT).asType();
        final var integerClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(
                null,
                "java.lang.Integer"
        );
        integerClass.complete();
        final var numberClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(
                null,
                "java.lang.Number"
        );
        numberClass.complete();

        assertTrue(isSubType.visitWildcardType(
                new CWildcardType(null, BoundKind.UNBOUND, null),
                new CTypeVariable("E"))
        );
        assertTrue(isSubType.visitWildcardType(
                new CWildcardType(objectType, BoundKind.EXTENDS, null),
                new CTypeVariable("E"))
        );
        assertTrue(isSubType.visitWildcardType(
                new CWildcardType(integerClass.asType(), BoundKind.SUPER, null),
                new CTypeVariable("E", null, numberClass.asType(), null))
        );
    }

    @Test
    void typeVariableSubtypeOfUpperBound() {
        final var objectType = getCompilerContext().getClassElementLoader().loadClass(null, Constants.OBJECT).asType();
        final var numberClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.Number");
        numberClass.complete();
        final var typeVar = new CTypeVariable("T", null, numberClass.asType(), null);

        assertTrue(isSubType.visitTypeVariable(typeVar, objectType));
        assertTrue(isSubType.visitTypeVariable(typeVar, numberClass.asType()));
    }

    @Test
    void typeVariableWithLowerBoundSubtypeOfWildcard() {
        final var objectType = getCompilerContext().getClassElementLoader().loadClass(null, Constants.OBJECT).asType();
        final var numberClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.Number");
        numberClass.complete();
        final var integerClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.Integer");
        integerClass.complete();

        final var typeVar = new CTypeVariable("T", null, null, integerClass.asType());
        final var wildcardExtends = new CWildcardType(numberClass.asType(), BoundKind.EXTENDS, null);

        assertTrue(isSubType.visitTypeVariable(typeVar, wildcardExtends));
    }

    @Test
    void typeVariableEqualityWithWildcard() {
        final var objectType = getCompilerContext().getClassElementLoader().loadClass(null, Constants.OBJECT).asType();
        final var typeVar = new CTypeVariable("T", null, objectType, null);
        final var wildcardUnbound = new CWildcardType(null, BoundKind.UNBOUND, null);

        assertTrue(isSubType.visitTypeVariable(typeVar, wildcardUnbound));
    }
}


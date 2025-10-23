package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.CWildcardType;
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.type.BoundKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IsSubTypeTest extends AbstractCompilerTest {

    private final TypesImpl types = getCompilerContext()
            .getClassElementLoader().getTypes();
    private IsSubType isSubType = new IsSubType(types);

    @Test
    void visitWildcardType() {
        final var objectType = types.getObjectType();
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
}


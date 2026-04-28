package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AsmHelperTest {

    @Test
    void createDescriptorWithValues() {
        final var descriptor = AsmHelper.createDescriptorWithValues(
                List.of(
                        new IRValue.Temp("value", IRType.I8)
                ),
                IRType.BOOL
        );
        assertEquals("(B)Z", descriptor);
    }

    @Test
    void createDescriptor() {
    }

    @Test
    void testCreateDescriptor() {
    }

    @Test
    void toInternalName() {
    }

    @Test
    void testCreateDescriptor1() {
    }

    @Test
    void testToInternalName() {
        final var integerClass = new ClassSymbol(
                0, "Integer",
                new PackageSymbol(new PackageSymbol(null, "java"), "lang")
        );

        final var actual = AsmHelper.toInternalName(
                new IRType.Ptr(
                        IRType.I8,
                        new CClassType(null, integerClass, List.of())
                )
        );

        assertEquals("java/lang/Integer", actual);
    }
}
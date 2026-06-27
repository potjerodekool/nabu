package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.CWildcardType;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.type.BoundKind;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeVariable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TypeMapFillerTest extends AbstractCompilerTest {

    @Test
    void visitDeclaredType() {
        final var typeMapFiller = new TypeMapFiller(getCompilerContext().getTypes());
        final var consumerClass = loadClass(Consumer.class.getName());

        final var wildcard = new CWildcardType(
                new CTypeVariable("T"),
                BoundKind.SUPER,
                null
        );

        final var consumerType = getCompilerContext()
                .getTypes()
                .getDeclaredType(
                        consumerClass,
                        wildcard
                );

        final var integerType = loadClass(Constants.INTEGER).asType();
        final var otherType = new CMethodType(
                null,
                null,
                List.of(),
                getCompilerContext().getTypes()
                        .getNoType(TypeKind.VOID),
                List.of(integerType),
                List.of()
        );

        typeMapFiller.visitDeclaredType(consumerType, otherType);

    }
}
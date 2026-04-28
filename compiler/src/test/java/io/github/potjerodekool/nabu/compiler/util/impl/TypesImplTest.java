package io.github.potjerodekool.nabu.compiler.util.impl;

import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypesImplTest extends AbstractCompilerTest {

    @Test
    void directSupertypes() {
        final var stringType = loadClass(Constants.STRING).asType();
        final var directSuperTypes = getCompilerContext().getTypes().directSupertypes(stringType);
        final var actual = directSuperTypes.stream()
                .map(it -> (DeclaredType) it)
                .map(it -> it.asTypeElement().getQualifiedName())
                        .toList();
        final var expected = List.of(
                "java.lang.Object",
                "java.io.Serializable",
                "java.lang.Comparable",
                "java.lang.CharSequence",
                "java.lang.constant.Constable",
                "java.lang.constant.ConstantDesc"
        );
        assertEquals(6, directSuperTypes.size());
        assertEquals(expected, actual);
    }
}
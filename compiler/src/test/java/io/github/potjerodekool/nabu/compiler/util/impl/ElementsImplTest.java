package io.github.potjerodekool.nabu.compiler.util.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.lang.model.element.CCompoundAttribute;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.type.DeclaredType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;

class ElementsImplTest {

    private final SymbolTable symbolTable = Mockito.mock(SymbolTable.class);

    private final ElementsImpl elements = new ElementsImpl(
            symbolTable,
            null,
            null
    );

    @Test
    void getAllAnnotationMirrors() {
        final var inherited = new ClassSymbolBuilder()
                .kind(ElementKind.ANNOTATION_TYPE)
                .simpleName("Inherited")
                .build();

        when(symbolTable.getInheritedType())
                .thenReturn(inherited.asType());

        final var objectClass = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("Object")
                .build();

        when(symbolTable.getObjectType())
                .thenReturn(objectClass.asType());

        final var inheritedAnnotation = new CCompoundAttribute(
                (DeclaredType) inherited.asType(),
                Map.of()
        );

        final var annotationType = new ClassSymbolBuilder()
                .kind(ElementKind.ANNOTATION_TYPE)
                .annotations(List.of(inheritedAnnotation))
                .build();

        final var parentClassAnnotation = new CCompoundAttribute(
                (DeclaredType) annotationType.asType(),
                Map.of()
        );

        final var subClassAnnotation = new CCompoundAttribute(
                (DeclaredType) annotationType.asType(),
                Map.of()
        );

        final var parentClazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("ParentClass")
                .superclass(objectClass.asType())
                .annotations(List.of(parentClassAnnotation))
                .build();

        final var subClazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("SubClass")
                .superclass(parentClazz.asType())
                .annotations(List.of(subClassAnnotation))
                        .build();

        elements.getAllAnnotationMirrors(subClazz);
    }
}
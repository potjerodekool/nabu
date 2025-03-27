package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.CArrayAttributeProxy;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.CCompoundAttribute;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.impl.CArrayType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class AnnotationDeProxyProcessorTest {

    private final AnnotationDeProxyProcessor processor = new AnnotationDeProxyProcessor();

    @Test
    void process() {
        final var elementTypeClass = new ClassSymbolBuilder()
                .kind(ElementKind.ENUM)
                .name("ElementType")
                .build();

        final var simpleMethod = new MethodSymbolBuilderImpl()
                .name("value")
                .enclosingElement(elementTypeClass)
                .build();

        final var valuesAttribute = new CArrayAttributeProxy(
                List.of()
        );

        final var targetClass = (ClassSymbol) new ClassSymbolBuilder()
                .kind(ElementKind.ANNOTATION_TYPE)
                .name("Target")
                .build();

        final var method = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .name("value")
                .returnType(new CArrayType(elementTypeClass.asType()))
                .enclosingElement(targetClass)
                .build();

        targetClass.addEnclosedElement(method);

        final var before = new CCompoundAttribute(
                (DeclaredType) targetClass.asType(),
                Map.of(
                        simpleMethod,
                        valuesAttribute
                )
        );

        final var newAnnotation = processor.process(before);
        System.out.println(newAnnotation);
    }
}
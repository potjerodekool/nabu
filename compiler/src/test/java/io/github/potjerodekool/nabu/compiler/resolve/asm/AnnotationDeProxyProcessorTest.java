package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.impl.ArrayAttributeProxy;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.CompoundAttribute;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.impl.CArrayType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class AnnotationDeProxyProcessorTest {

    private final AnnotationDeProxyProcessor processor = new AnnotationDeProxyProcessor();

    @Test
    void process() {
        final var simpleMethod = new MethodBuilder()
                .name("value")
                .build();

        final var valuesAttribute = new ArrayAttributeProxy(
                List.of()
        );

        final var elementTypeClass = new ClassBuilder()
                .name("ElementType")
                .build();

        final var method = (MethodSymbol) new MethodBuilder()
                .name("value")
                .returnType(new CArrayType(elementTypeClass.asType()))
                .build();

        final var targetClass = new ClassBuilder()
                .name("Target")
                .enclosedElement(method)
                .build();

        final var before = new CompoundAttribute(
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
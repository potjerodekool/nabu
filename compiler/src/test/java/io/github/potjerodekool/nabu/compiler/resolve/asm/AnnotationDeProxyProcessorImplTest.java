package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.lang.model.element.CArrayAttributeProxy;
import io.github.potjerodekool.nabu.lang.model.element.CCompoundAttribute;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CArrayType;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.type.DeclaredType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class AnnotationDeProxyProcessorImplTest {

    private final AnnotationDeProxyProcessorImpl processor = new AnnotationDeProxyProcessorImpl();

    @Test
    void process() {
        final var elementTypeClass = new ClassSymbolBuilder()
                .kind(ElementKind.ENUM)
                .simpleName("ElementType")
                .build();

        final var simpleMethod = new MethodSymbolBuilderImpl()
                .simpleName("value")
                .enclosingElement(elementTypeClass)
                .build();

        final var valuesAttribute = new CArrayAttributeProxy(
                List.of()
        );

        final var targetClass = (ClassSymbol) new ClassSymbolBuilder()
                .kind(ElementKind.ANNOTATION_TYPE)
                .simpleName("Target")
                .build();

        final var method = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("value")
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